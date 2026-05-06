# Agent Tool Contract v1

Agent Platform tools are not ordinary application APIs. They are the action
surface that an LLM uses to plan, search, inspect, act, verify, and explain
work on the user's behalf. The tool schema is therefore part API contract, part
planner instruction, part safety boundary, and part UI rendering contract.

This document defines the first project-level standard for agent-facing tools.

## Goals

- Make tool behavior predictable for the model.
- Encode user intent in parameters instead of relying on prompt guessing.
- Separate recall candidates from confirmed results.
- Make frontend rendering deterministic after live streaming and history replay.
- Preserve auditability: every important choice should be visible in args or
  result metadata.
- Put safety at the tool boundary, not only in system prompts.

## Core Rule

If a user can ask for a constraint naturally, the tool should expose that
constraint explicitly.

Examples:

- "latest cat photo" means semantic recall plus capture-time sorting.
- "yesterday's WeChat screenshot" means date filters plus app/source filters.
- "tap Send" should prefer a semantic UI target over raw coordinates.
- "show me one photo" should not render eight internal candidates as final
  user-visible results.

## Tool Classes

Each tool should have one primary class.

| Class | Purpose | Examples | Default safety |
| --- | --- | --- | --- |
| `search` | Find possible entities | photos, videos, messages, files | read-only |
| `inspect` | Fetch detail for known entity | full photo, metadata, screenshot | read-only |
| `act` | Mutate device/app/user state | tap, type, swipe, open app | confirm when sensitive |
| `verify` | Check current state or action result | dump tree, wait for text | read-only |
| `meta` | Agent-side helper | skill load, memory recall | read-only |

## Naming

Use stable dot names:

```text
domain.action
```

Examples:

```text
photos.semantic_search
photos.get_full
ui.dump_tree
ui.tap
ui.tap_node
```

Names must reveal the main side effect. If a tool can perform several actions,
use an explicit enum parameter.

## Schema Requirements

Every public agent tool should define:

- Precise description: what it does, when to use it, and what it does not do.
- JSON Schema types, enums, bounds, defaults, and units.
- Required fields only when no safe default exists.
- Distinction between final user-visible count and internal candidate count.
- Explicit safety through `confirm_required`.
- Result display expectations, either in the description or result metadata.

Search-like tools should expose these concepts when applicable:

```json
{
  "query": "cat",
  "filters": {
    "bucket_id": null,
    "name_contains": null,
    "package_name": null,
    "date_after": null,
    "date_before": null
  },
  "ranking": {
    "mode": "semantic",
    "candidate_k": 50,
    "min_score": 0.2
  },
  "sort": {
    "by": "relevance",
    "direction": "desc"
  },
  "limit": 1,
  "review_limit": 8,
  "display": "confirmed_only"
}
```

The implementation may flatten these fields for compatibility, but the concepts
must be present when the tool supports them.

## Time Parameters

Use Unix milliseconds in UTC for tool parameters:

```text
date_after
date_before
```

Descriptions must state which timestamp is used:

| Field | Meaning |
| --- | --- |
| `date_taken_ms` | Photo/video capture time. |
| `date_modified_sec` | MediaStore/filesystem modified time. |
| `created_at` | Server row creation time. |
| `updated_at` | Server row update time. |

Natural language like "latest", "recent", "newest", or "last one" must map to
an explicit sort/ranking mode. Prompt text alone is not enough.

## Search And Ranking

Search tools should separate five steps:

1. Recall: fetch enough possible matches.
2. Filter: apply hard metadata constraints.
3. Rank: order by relevance, time, name, or a hybrid score.
4. Confirm: inspect one or more candidates when certainty matters.
5. Display: show results in the shape the user requested.

Recommended ranking modes:

| Mode | Meaning |
| --- | --- |
| `semantic` | Sort by vector/text/image similarity only. |
| `metadata` | Sort/filter by metadata only. |
| `semantic_then_sort` | Recall by semantic score, keep only qualifying hits near the best match, then sort those hits by metadata. |
| `sort_then_semantic` | Restrict/sort by metadata first, then rank semantically. |
| `hybrid` | Combine semantic and metadata scores. |

Recommended sort fields:

| Sort | Meaning |
| --- | --- |
| `relevance` | Semantic/local score. |
| `date_taken` | Capture time. |
| `date_modified` | Media modified time. |
| `created_at` | Server creation time. |
| `updated_at` | Server update time. |
| `name` | Filename/display name. |

For "latest X", the normalized request should be:

```json
{
  "query": "cat kitten pet cat photo",
  "ranking": {
    "mode": "semantic_then_sort",
    "candidate_k": 50,
    "min_score": 0.2
  },
  "sort": {
    "by": "date_taken",
    "direction": "desc"
  },
  "limit": 1,
  "review_limit": 8,
  "display": "confirmed_only"
}
```

`semantic_then_sort` must not let a weak semantic match win only because it is
newer. Implementations should first select semantically qualified candidates,
for example by keeping hits above `max(min_score, best_score - 0.06,
best_score * 0.8)`, and only then apply the metadata sort.

## Candidates Versus Confirmed Results

Search results are candidates unless the tool can certify them.

Candidate result metadata:

```json
{
  "result_type": "candidates",
  "candidate_only": true,
  "display_policy": "hidden_candidates"
}
```

Confirmed result metadata:

```json
{
  "result_type": "confirmed",
  "candidate_only": false,
  "display_policy": "show_primary"
}
```

Frontend rules:

- Do not render `hidden_candidates` as final answer content.
- Store candidates for audit/replay/debug.
- Collapse candidates unless the user asks to inspect them.
- Render primary media from an inspect tool such as `photos.get_full`.

## Return Envelope

New or refactored tools should move toward this shape:

```json
{
  "ok": true,
  "schema_version": "agent_tool_contract/v1",
  "tool": "photos.semantic_search",
  "request": {
    "query": "cat",
    "limit": 1
  },
  "result_type": "candidates",
  "items": [],
  "summary": {
    "count": 8,
    "scanned": 80
  },
  "confidence": {
    "label": "medium",
    "score": 0.72,
    "reason": "semantic candidates need visual confirmation"
  },
  "display": {
    "policy": "hidden_candidates"
  },
  "next": {
    "recommended_tool": "photos.get_full",
    "args": {
      "id": "1000031781",
      "max_dim": 2048
    }
  }
}
```

Existing fields may stay for compatibility, but standard fields should be added
when a tool is changed.

## Error Envelope

Errors should be machine-readable and model-readable:

```json
{
  "ok": false,
  "error": {
    "code": "permission_missing",
    "message": "Media permission is not granted.",
    "retryable": false,
    "hint": "Ask the user to grant photo permission in Android settings."
  }
}
```

Avoid plain text-only errors when the caller needs to choose a recovery path.

## Display Policy

Renderable tools should state how the UI should display results.

| Policy | Meaning |
| --- | --- |
| `show_primary` | Show one primary final result. |
| `show_grid` | Show multiple final results. |
| `collapsed_candidates` | Candidate results may be opened manually. |
| `hidden_candidates` | Store for replay/debug but do not show as answer content. |
| `debug_only` | Show only in developer/debug views. |

Binary fields:

| Field | Meaning |
| --- | --- |
| `thumb_b64` | Small UI preview. |
| `vision_b64` | High-detail image for model vision. |

The LLM must never receive base64 as plain text. Server-side binary stripping or
native media attachment handling is mandatory.

## Safety

Safety must be declared at the tool boundary.

| Level | Meaning | Examples |
| --- | --- | --- |
| `read_only` | Cannot change user/device state. | list photos, dump tree |
| `local_state` | Low-risk local mutation. | open app, change focus |
| `sensitive_action` | Can send, delete, pay, post, type, or navigate sensitive UI. | tap, type text |
| `dangerous` | High-impact or irreversible. | payment confirmation, deletion, account changes |

Rules:

- `confirm_required` must be enforced by server/device infrastructure.
- Prompt-level confirmation is a fallback, not the primary safety control.
- Mutating tools should return dispatch status and, when possible, verification
  status.
- Prefer high-level semantic actions over raw coordinates.

## UI Tool Standard

Raw coordinate tools are primitives. Agent workflows should prefer semantic
tools when possible.

Recommended future tools:

```text
ui.find_node
ui.tap_node
ui.wait_for
ui.assert_screen
```

Example:

```json
{
  "target": {
    "text": "Send",
    "role": "button",
    "package": "com.tencent.mm"
  },
  "ambiguity": "fail_if_multiple",
  "confirm_before": true,
  "verify_after": {
    "wait_for_text": "Sent",
    "timeout_ms": 3000
  }
}
```

Raw `ui.tap` should be used only after `ui.dump_tree` or `ui.screen_capture`
provides reliable coordinates.

## Photo Tool Standard

`photos.semantic_search` should support:

- `query`
- filters: `bucket_id`, `name_contains`, `date_after`, `date_before`
- `ranking.mode`
- `ranking.candidate_k`
- `ranking.min_score`
- `sort.by`
- `sort.direction`
- `limit`: final user-visible count
- `review_limit`: internal candidate count for audit/debug, not a normal result count
- `display`: candidate/result rendering policy

For "latest cat photo":

```json
{
  "query": "cat kitten pet cat photo",
  "ranking": {
    "mode": "semantic_then_sort",
    "candidate_k": 50,
    "min_score": 0.2
  },
  "sort": {
    "by": "date_taken",
    "direction": "desc"
  },
  "limit": 1,
  "review_limit": 8,
  "display": "confirmed_only"
}
```

The `photos` field must contain only the final user-visible result count
(`limit`). The service should query enough semantic candidates internally with
`candidate_k`, then return only the selected `limit` rows. Internal
recall/review items may be exposed as `review_candidates` only when the caller
explicitly asks to debug/browse candidates (for example `display:
show_candidates`), and those entries must not include `thumb_b64`,
`vision_b64`, or any other image binary. For `limit: 1`, the tool should include either a
`primary_image` result from `photos.get_full` or a `next.recommended_tool`
pointing at `photos.get_full` for that single id.

## Audit And Replay

Every chat-visible tool call should persist:

- canonical tool name
- normalized arguments
- result metadata
- duration
- result type
- display policy
- error code if failed

History replay must not depend on transient SSE-only state. If a result is shown
during live streaming, enough render metadata must be stored to render it after
switching sessions.

## Versioning

Tools should include:

```json
{
  "schema_version": "agent_tool_contract/v1"
}
```

Compatibility rules:

- Additive fields are allowed.
- Existing field meanings must not change silently.
- Breaking changes require a new tool name or explicit versioned behavior.
- Keep legacy aliases only while the agent can still understand the result.

## Migration Plan

1. Add standard result metadata to new tool results.
2. Upgrade `photos.semantic_search` to expose ranking and sorting.
3. Update frontend rendering to obey `display_policy`.
4. Add semantic UI tools on top of current coordinate primitives.
5. Add tests for schema defaults, sorting modes, result envelopes, and replay.
