<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **agent-platform** (6535 symbols, 16425 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/agent-platform/context` | Codebase overview, check index freshness |
| `gitnexus://repo/agent-platform/clusters` | All functional areas |
| `gitnexus://repo/agent-platform/processes` | All execution flows |
| `gitnexus://repo/agent-platform/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

## Agent Tool Design

- Design tools as agent action surfaces, not ordinary backend APIs and not
  black-box business buttons. A good tool exposes composable capabilities,
  structured state, stable handles, display policy, safety/confirmation
  behavior, and replayable references. Keep subjective or business-specific
  judgment in the agent/skill layer: tools provide capability and state, skills
  orchestrate workflow, and the agent inspects, judges, explains, and asks for
  confirmation.
- Avoid both extremes: thin CRUD/list/get/delete wrappers that force the agent
  into slow mechanical loops, and overly custom tools that hide the reasoning
  behind one business action such as "cleanup candidates". Prefer reusable
  primitives such as query/search, batch inspect, selection/set creation,
  action-by-id-or-selection, and verify.

## Product Quality Bar

- Do not ship "MVP-only" implementations for user-facing or agent-facing
  features unless the user explicitly asks for a throwaway prototype. Build to
  the actual business workflow and expected standard: complete controls and
  states, durable contracts, sensible edge-case handling, verification, and an
  experience that feels fit for real use rather than a thin demo.

## Runtime / Deployment Location

- The live service stack is deployed only on Megumin (`root@192.168.0.109`,
  `/opt/agent-platform`). For live sessions, service logs, Postgres data,
  container status, health checks, and deployment verification, inspect the
  Megumin compose stack over SSH.
- Do not look for backend/server containers on the local workstation unless the
  user explicitly says a local dev stack is running. Local work is for source
  edits, local builds/tests, Android APK builds, and packaging/publishing
  deployment artifacts.
