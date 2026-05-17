---
name: agent-platform
description: Project skill for D:\agent-platform, a self-hosted mobile agent platform using Java 21, Spring Boot 3.5, Spring Cloud, Spring AI, React, and Kotlin Compose Android. Use when working in this repo or when the user asks about architecture, device tools, prompt/persona/skills/memory, packaged or runtime skills, Codex or Anthropic provider routing, semantic photo search, session UI, Android builds/APK install, GitNexus workflow, GHCR image publishing, or megumin docker-compose deployment.
---

# Agent Platform — Project Skill

## Architecture (1-minute read)

```
[Web/SSE]──┐
           ├──▶ gateway ──▶ agent-service ──▶ Spring AI ChatClient ──▶ Anthropic (or compatible)
           │                       │
           │                       ▼ tool dispatch
           │                  device-hub-service ◀── WebSocket ── Android FGS
           │                       │                                  │
           │                       ▼                                  ▼
           │                  PendingCallRegistry              ToolRegistry (photos.list_recent, ...)
           │                  (DeferredResult)                 MediaStore / etc.
           │
           └─▶ auth-service (users / devices / enrollments / JWT)
                  │
                  ▼
              chat-service (sessions / messages / memory_facts (pgvector))
```

- 8 Spring Cloud services (auth/agent/chat/hub/gateway) + Nacos discovery + Postgres (pgvector) + Caddy TLS + nginx static web.
- LLM tool list dynamically registered per chat request from currently online devices.
- WS protocol is raw JSON-RPC 2.0 (form-similar to MCP, not wire-compatible).
- See `README.md` and `pom.xml` for full module layout.

## Routing — pick by the symptom

| Problem | Files / commands |
|---|---|
| LLM behavior off (tone, tool use, recall) | `agent-service/.../chat/ChatService.java` + `agent-service/src/main/resources/persona/{SOUL,IDENTITY,AGENTS,TOOLS}.md` + `skills/*/SKILL.md` |
| Tool call timing out / WS flapping | `device-hub-service/.../ws/DeviceWsHandler.java` + Android `AgentForegroundService.kt` + `docker logs agent-platform-hub` |
| DB schema change | `<service>/src/main/resources/db/migration/V<n>__<desc>.sql` (Flyway runs on startup) |
| Web UI | `web/src/pages/*.tsx` then rebuild + redeploy nginx |
| Android | `android/app/src/main/java/com/agentplatform/android/...` + `gradlew assembleDebug` |
| New device tool | Android `Tool.kt` impl + register in `AgentForegroundService.toolRegistry` (server zero-change) |
| Slow repeated mobile UI tool calls | Use/extend Android `ui.run_steps` for known ordered UI workflows; keep exploratory `ui.dump_tree`/`ui.screen_capture` for unknown pages |
| Semantic photo search noisy or wrong | `PhotosSemanticCandidatesTool.kt` + `SemanticPhotoSearchCallback.java` + `skills/photos-search/SKILL.md` + `ChatPage.tsx` |
| Provider/model routing | `AgentBeans`, `CodexResponsesLoopRunner`, `.env`, server `agent-platform-agent` logs |
| Background LLM / LangChain4j | `LangChain4jModelFactory`, `RoutingBackgroundChatModel`, `BackgroundFactExtractor`, `SessionSummarizer`, `BackgroundLlmClient`, `EmbeddingService` |
| GHCR / megumin deploy | build locally first, package the already-built artifact into GHCR, then `ssh root@192.168.0.109` and `/opt/agent-platform` |

## Adding a new device tool (the canonical workflow)

1. Implement `com.agentplatform.android.core.tool.Tool` in `android/app/src/main/java/com/agentplatform/android/tools/<domain>/<Name>Tool.kt` — see `PhotosListRecentTool.kt` as a reference. `name` uses dot namespace (`<domain>.<action>`); `schema` is a JSON Schema object the LLM sees; `description` should be specific enough that the LLM picks it correctly.
2. Register in `AgentForegroundService.onCreate`: `toolRegistry.register(YourTool(applicationContext, mapper))`.
3. Add any runtime permissions to `AndroidManifest.xml` and (if Android 13+ runtime grant required) wire a `PermissionCard` in `BoundScreen.kt`.
4. `./gradlew assembleDebug` in `android/`. Reinstall APK; on next reconnect the device pushes its updated tool manifest, and the LLM sees the new tool on the very next chat.

**Server-side: zero changes needed.** That's the headline feature of the platform.

## Mobile UI tool speed pattern

- The server/provider may emit multiple tool calls, and Android can run independent non-UI tools concurrently, but all `ui.*` operations share the foreground screen and are serialized by `AgentForegroundService.uiToolMutex`.
- Do not speed up UI automation by firing parallel taps/swipes/types. Use `ui.run_steps` to send a short ordered macro to the phone in one tool call.
- `ui.run_steps` supports `open_app`, `tap`, `long_press`, `swipe`, `type_text`, `global`, `wait`, and `dump_tree`. Prefer `observe=final` or `observe=on_failure`; use `observe=after_each` only while debugging a flaky flow.
- First-time app learning still starts with `ui.dump_tree` and sometimes `ui.screen_capture`. Project/runtime skills should store stable package names, page recognition patterns, node ids/bounds, safe workflow segments, and safety stops. Later turns can execute those known segments through `ui.run_steps` instead of one observe-after-each-action loop.
- To save an image shown inside another Android app, locate the image bounds, call `ui.long_press` or `ui.run_steps` action `long_press`, wait for the app context menu, tap the visible save action (`保存图片`, `保存到手机`, `保存到相册`, etc.), then verify/show it with `photos.list_recent`, `photos.recent_screenshots`, `photos.semantic_search`, or `photos.get_full`.
- Keep dangerous or externally consequential steps (pay, submit order, delete, send, permission acceptance) outside macros unless the user explicitly confirmed the exact action.
- Android MediaStore mutations such as `photos.trash` still launch a system confirmation PendingIntent. After the agent-side confirmation has already happened, the Android client may arm a short-lived MediaStore-only auto-approve window and use the Agent Platform accessibility service to click that specific system dialog. Do not generalize this into a global "click any allow button" behavior.

## Adding a new skill (LLM playbook)

There are two skill tiers:

1. **Runtime skills**: agent-created per-user skills stored in chat-service `runtime_skills`. The agent can create/update/list/delete these through server-side tools (`agent_skill_upsert`, `agent_skill_list`, `agent_skill_delete`). It can also install a standard `SKILL.md` through `agent_skill_install` from pasted markdown or a trusted HTTPS raw URL. Runtime skills are visible in the skill index on the next turn and override packaged skills by name. No rebuild/redeploy needed.
2. **Packaged skills**: developer-shipped skills under `agent-service/src/main/resources/skills/<slug>/SKILL.md` with YAML frontmatter `name` + `description`. Rebuild `agent-service` and redeploy; `SkillRegistry` rescans classpath on startup.

For stable user/project facts and lessons, the agent can also save curated memory through `agent_memory_add`; this writes `memory_facts.is_curated=true` and is recalled by normal memory search.

## Modifying LLM behavior (persona)

The system prompt is assembled in `ChatService.handleWithLlm` from 4 markdowns plus the user's `user_preferences` row plus a memory recall block:

```
# IDENTITY        (persona/IDENTITY.md — who the agent is)
# SOUL            (persona/SOUL.md — behavioral principles)
# AGENTS          (persona/AGENTS.md — startup order, red lines, decision trees)
# TOOLS           (persona/TOOLS.md — environment-specific tool conventions)
# USER            (auth.user_preferences.content — user's AGENTS.md analogue)
# AVAILABLE SKILLS (skill names + descriptions; bodies loaded via skill_load)
# RELEVANT MEMORIES (UNTRUSTED) (top-K from memory_facts, curated tier first)
```

Edit the relevant `.md` file and redeploy `agent-service`.

## Memory (`chat.memory_facts` + `chat.memory_embeddings`)

- `MemoryExtractor` (in agent-service) runs after each chat turn: a cheap LLM extracts facts/preferences from the user/assistant exchange, embeds them, and writes via Feign to chat-service `POST /internal/memory/facts`.
- Background fact extraction and rolling session summary are LangChain4j Spring AI Services: `BackgroundFactExtractor` and `SessionSummarizer` use `@AiService(wiringMode = EXPLICIT, chatModel = "...")`.
- Those AI Services do not bind directly to one vendor. Their named `ChatModel` beans are `RoutingBackgroundChatModel`, which preserves the existing configured provider pool and fallback order while presenting a LangChain4j `ChatModel` to the framework.
- `LangChain4jModelFactory` builds per-provider background `ChatModel`s for Anthropic Messages and OpenAI-compatible Responses. The live mobile agent loop still stays custom in `AgentLoopRunner` / `CodexResponsesLoopRunner`, because it needs provider-specific streaming, tool-call replay, image handling, cancellation, budgets, and frontend SSE events.
- Standard OpenAI-compatible embeddings can use LangChain4j `OpenAiEmbeddingModel` when `MEMORY_PREFER_LANGCHAIN4J_EMBEDDINGS=true` and `MEMORY_EMBEDDING_TASK` is blank. Jina v3 needs provider-specific `task`, so that path intentionally keeps the custom HTTP request.
- On the next turn, `recallMemories` queries `POST /internal/memory/facts/query` (pgvector cosine, two-stage: curated first, then raw).
- `POST /internal/memory/promote` periodically elevates frequently-hit raw facts to `is_curated=true`.

## LangChain4j rules

- Before changing LangChain4j wiring, read the official Spring Boot integration docs: `https://langchain4j.cn/tutorials/spring-boot-integration.html` (or `https://docs.langchain4j.dev/tutorials/spring-boot-integration/` if the mirror is slow).
- Use LangChain4j for framework-shaped background LLM tasks: fact extraction, session summaries, simple non-streaming completions, and standard embedding clients.
- Keep the main mobile agent conversation loop custom unless LangChain4j can exactly preserve OpenAI Responses SSE events, Anthropic streaming blocks, Android tool schema sanitization, per-turn tool budgets, cancellation, and web SSE rendering.
- When multiple `ChatModel` beans exist, `@AiService` must use explicit wiring with a real bean name. Missing or ambiguous model beans can break Spring startup.
- Do not mutate a LangChain4j `ChatRequest` with `toBuilder().modelName(...)` if the original request has `parameters`; LangChain4j rejects requests containing both `parameters` and individual fields such as `modelName`. Rebuild a fresh `ChatRequest` and copy only the needed fields; keep `RoutingBackgroundChatModelTest`.

## Context management

- `ContextAssembler` is the single boundary for per-turn LLM context. Keep new context policy there instead of adding more prompt assembly logic directly in `ChatService`.
- Current V2 layers: stable system prompt, recalled long-term memory, session working set, rolling session summary, recent USER/ASSISTANT history, then the current user message.
- `chat.session_artifacts` stores lightweight working-set references from tool results. It stores IDs, result rank, titles, summaries, and bounded metadata, not original images or large base64 payloads.
- `ToolArtifactExtractor` extracts lightweight artifacts from `photos.*`, `ui.screen_capture`, and `ui.dump_tree` tool results. This lets the next turn resolve "this image", "that result", "this screen", or "the current page" without replaying bulky tool output.
- UI artifacts store only bounded metadata such as source, screen dimensions, package/activity hints, and node counts. They must not store screenshot base64 or full accessibility trees.
- Tool result rows remain the display/audit source. Artifacts are for model context references only; detailed visual claims still require fetching full/current content with an appropriate tool.
- `chat.session_context_summaries` stores a compact rolling summary for old USER/ASSISTANT turns. `SessionSummaryRefresher` updates it asynchronously after a complete assistant reply; failures must never fail the chat path.
- `ContextBudget` estimates context tokens and keeps only the recent tail verbatim (`CONTEXT_RECENT_HISTORY_MESSAGES`, default 12). Only trim history when `ContextAssembler` has a usable `SessionContextSummaryDto` (`coveredMessageCount > 0` and non-blank `summary`). If summary generation fails or no summary row exists, replay full USER/ASSISTANT history instead of dropping old turns. If `CONTEXT_ENABLE_SESSION_SUMMARY=false`, history replay falls back to full legacy behavior.
- Context knobs: `CONTEXT_ENABLE_SESSION_SUMMARY`, `CONTEXT_RECENT_HISTORY_MESSAGES`, `CONTEXT_SUMMARY_TRIGGER_MESSAGES`, `CONTEXT_MAX_INPUT_TOKENS`, `CONTEXT_SUMMARY_MAX_TOKENS`.
- Dynamic current time lives in the per-request `# CURRENT TIME` user-context block, not the prompt-cache-stable system block.

## Semantic photo search

- `photos.semantic_search` is a recall/ranking tool, not a certified classifier. It scans recent/gallery images on Android, runs OCR + ML Kit image labels, sends candidate text/metadata to agent-service, embeds query and candidate text, then returns top candidates.
- Default `limit` should stay small. Use `limit:1` when the user asks for one image, "那张", or "最近一张"; use `3-5` for ambiguous searches; only use larger limits when the user explicitly wants many images.
- Frontend should treat semantic results as candidates. Show a few primary thumbnails and fold extras; do not make the UI look like every candidate is a confirmed match.
- For content questions about a specific image, call `photos.get_full` for the returned `id` and let the vision model inspect the full image. OCR and thumbnails are hints, not final evidence.
- Chinese natural-photo searches rely on ML Kit labels plus aliases such as `cat -> 猫/猫咪/宠物/动物`; screenshots/text-heavy images rely more on OCR.

## Tool hooks

`RemoteToolCallback.call` runs:
1. `List<ToolPreInterceptor>` synchronously — interceptors may rewrite `args` or throw `ToolBlockedException`.
2. `dispatcher.dispatch(...)` (HTTP DeferredResult into device-hub).
3. `ApplicationEventPublisher.publishEvent(new ToolPostEvent(...))` for async audit/metrics.

Add a new interceptor by creating a `@Component implements ToolPreInterceptor` — Spring auto-collects.

## Agent tool contract

- Tool design standard: `docs/agent-tool-contract-v1.md`.
- Agent-facing tools should expose intent, filters, ranking/sort, final result
  count versus review candidates, display policy, safety/confirmation behavior,
  and replayable result metadata.
- Frontend tool-result rendering should prefer standard metadata (`display_policy`,
  `display.policy`, `result_type`, `candidate_only`, `primary_image`, `items`,
  `photos`) over hard-coded tool names. Keep tool-name checks only as legacy
  fallback for old session history.
- For photo search, "latest/recent X" must be represented as semantic recall
  plus metadata sort, not as plain vector top-K.

## Pitfalls (the project has stepped on these — do not repeat)

1. **`docker compose down -v`** wipes user/device/chat data; only do it for **forward-incompatible** changes (changing postgres image, etc.) and confirm with the user first.
2. **`agent_chat` role's `search_path` is just `chat`**, so any `vector` type reference in V2/V3 migrations must be **fully qualified `public.vector(N)`**, and `MemoryService` must use `?::public.vector` casts.
3. **Do NOT add `spring-ai-starter-vector-store-pgvector`** to `chat-service/pom.xml`. Its auto-config requires an `EmbeddingModel` bean, which we don't want here (embeddings happen in agent-service against an external endpoint). We use raw JdbcTemplate.
4. **Tomcat WS default `maxTextMessageBufferSize` is 8KB**; base64 thumbnails are 50–100KB. `HubBeans.wsServletContainer()` bumps it to 4MB. Remember to redeploy this fix after `down -v + up -d` (image-baked jar reverts).
5. **Spring AI Anthropic auto-config picks up `@LoadBalanced WebClient.Builder`** by default and tries to resolve `subapi.example.com` as a Nacos service → 503. `AgentBeans` declares an `@Primary` plain (non-LB) builder to override.
6. **Anthropic tool names disallow `.`**. `RemoteToolCallback.sanitizeForLlm` rewrites `photos.list_recent` → `photos_list_recent` for the LLM only; canonical name still used over WS.
7. **Never feed `*_b64` fields to the LLM verbatim**. `RemoteToolCallback.stripB64ForLlm` swaps them for `<binary NB omitted>` placeholders. Frontend gets the original via the `tool_call_result` SSE event.
8. **Spring AI 1.x `ChatClient` has no native multi-provider fallback.** `ChatService` runs a synchronous `try / catch` chain over `List<ChatClient>` — only catches setup-phase errors, not in-stream reactor errors.
9. **OkHttp WS pingInterval is 15s** (not the default 30s), to keep home-router NAT mappings alive.
10. **Android 13+ runtime permissions** for `POST_NOTIFICATIONS` + `READ_MEDIA_IMAGES` aren't auto-requested at install; `BoundScreen` shows a card with deep-link to settings if missing.
11. **PowerShell mangles some Maven `-D...` args.** When Docker-running Maven with comma-separated test names or Surefire flags, wrap the command in container `sh -lc '...'`.
12. **Android builds need a real Android SDK.** A plain JDK Docker image can run Java tests but cannot compile the Android app unless `ANDROID_HOME`/`sdk.dir` points at a valid SDK.
13. **Remote `/opt/agent-platform/docker-compose.yml` currently uses `build:` images.** If deploying prebuilt GHCR images without copying an override, pull GHCR on megumin, tag to local names like `agent-platform-agent-service:latest`, then `docker compose --profile default up -d --no-build --force-recreate agent-service web`.
14. **Do not expose API keys in logs or final answers.** Provider keys live in `.env`, local CCS/proxy config, or server env. Confirm presence and routing, not raw secrets.
15. **PowerShell + SSH eats remote shell syntax.** Do not put remote `$(date ...)`, `$VAR`, or quote-heavy heredocs inside a PowerShell double-quoted `ssh "..."` command unless `$` is escaped. Prefer single-quoted outer SSH commands, a literal timestamp computed locally, or a base64/temp-script remote runner.
16. **Compose tag updates must be verified before restart.** When changing `/opt/agent-platform/docker-compose.ghcr-photo.yml`, use a simple `sed -i 's#old#new#g'` or uploaded script, then `grep` the file for the exact new GHCR tag before `docker compose pull/up`. Start remote mutation scripts with `set -e` so a failed replacement cannot continue into a restart with the old image.
17. **Health checks need readiness retry.** `docker compose up -d --force-recreate agent-service` can return before Spring Boot is listening on `8082`; a single immediate `curl` may falsely fail. Use a short retry loop against `/actuator/health` before treating deploy as broken.
18. **Remote base64 scripts must be literal before encoding.** In PowerShell, use a single-quoted here-string (`@' ... '@`) for remote bash. A double-quoted here-string expands remote `$(seq ...)` and `$VAR` locally, corrupting the script before it reaches megumin.
19. **Container health ports differ by service.** `agent-service` listens on 8082, but `chat-service` currently listens on 8084 in production; do not health-check chat on 8083.
20. **HyperOS/MIUI private appops must be set by numeric op.** `adb shell appops get com.agentplatform.android` prints entries like `MIUIOP(10021)`, but `appops set ... 'MIUIOP(10021)' allow` fails with `Unknown operation string`. Use the numeric op instead, e.g. `adb shell appops set --user 0 com.agentplatform.android 10021 allow`, then verify with a real hub `ui.open_app` call.
21. **`ui.open_app` can restore the last app page, not a cold-start home page.** Meituan Waimai (`com.sankuai.meituan.takeoutnew`) restored `GlobalSearchActivity` because the user had previously left it on a search results page. Always call `ui.dump_tree` after opening an app and classify the current page before deciding the next step; never assume the entry page.
22. **`ui.type_text` must not rely only on focused editable nodes.** Some apps show the keyboard but hide the target input from Accessibility as a normal `editable` node. Keep Android `UiAccessibilityService.typeText` layered: `ACTION_SET_TEXT` on focused node, Android 13+ accessibility input connection (`flagInputMethodEditor` in `accessibility_service_config.xml`), unique visible editable fallback, then clipboard `ACTION_PASTE`. Tool results should include `typed`, `method`, `reason`, and `editable_candidates` so the agent can recover instead of repeating blind taps.
23. **Session summary absence must never imply old history is summarized.** If `SessionSummaryRefresher` fails (for example `claude-haiku-4-5` returns 503/no accounts) and `chat.session_context_summaries` has no usable row, `ContextAssembler` must set `summaryPresent=false`, omit the summary block, and replay full history. Add/keep tests like `ContextAssemblerTest` for missing/blank/usable summaries before changing context compression.
24. **Anthropic rejects top-level JSON Schema combinators in tool `input_schema`.** Android device tools may legitimately expose richer JSON Schema (`oneOf`/`anyOf`/`allOf`), but Anthropic Messages rejects those keys at the root and then provider fallback fails. Keep the device schema expressive; sanitize only the Anthropic adapter in `RemoteToolCallback.buildInputSchema` by omitting root-level combinators while preserving `properties`, `required`, and ordinary additional schema fields. Add/keep tests like `RemoteToolCallbackSchemaTest`.
25. **GPT Responses rejects the same root-level JSON Schema keywords in tool `parameters`.** `subapi`/OpenAI can return `Invalid schema for function '<tool>'` when a device tool exposes root `oneOf`/`anyOf`/`allOf`/`enum`/`not` (for example `photos_trash`). Do not weaken Android schemas; sanitize only the GPT adapter in `CodexResponsesLoopRunner.normalizeSchema` by removing those root keys while preserving `type`, `properties`, `required`, and nested property schemas. Add/keep tests like `CodexResponsesLoopRunnerTest.buildToolsOmitsResponsesUnsupportedRootSchemaKeywords`.
26. **Android system MediaStore confirmations are a second confirmation layer.** The agent/tool confirmation (`confirmRequired`) does not remove Android's `MediaStore.createTrashRequest` / delete / favorite confirmation UI. For `photos.trash`, the Android client arms a short-lived MediaStore confirmation auto-approve using accessibility after the user already confirmed through the agent. Keep this scoped to the exact MediaStore flow and known system/media packages; never auto-click arbitrary permission or app dialogs.
27. **LangChain4j AI Service beans need explicit, real `ChatModel` names.** This project has multiple background models plus dynamic provider routing, so `@AiService` uses explicit wiring. `BackgroundFactExtractor` maps to `backgroundFactExtractorChatModel`; `SessionSummarizer` maps to `sessionSummarizerChatModel`. If either bean is renamed or removed, Spring startup can fail.
28. **LangChain4j `ChatRequest.toBuilder()` can carry `parameters`.** Setting `modelName`, token limits, or other individual fields on that builder can throw `Cannot set both 'parameters' and 'modelName'`. `RoutingBackgroundChatModel` rebuilds a clean request per provider; keep this pattern.

## Critical files (read these first when picking up the project)

1. `agent-service/src/main/java/com/agentplatform/agent/chat/ChatService.java` — prompt assembly + LLM stream + memory recall + B2 fact extraction trigger.
2. `agent-service/src/main/resources/persona/{SOUL,IDENTITY,AGENTS,TOOLS}.md` — agent's "soul" (4 markdowns).
3. `agent-service/src/main/java/com/agentplatform/agent/ai/RemoteToolCallback.java` — tool dispatch core (hooks, b64 strip).
4. `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java` — JSON-RPC routing.
5. `device-hub-service/src/main/java/com/agentplatform/hub/config/HubBeans.java` — WS 4MB buffer.
6. `android/app/src/main/java/com/agentplatform/android/service/AgentForegroundService.kt` — Android FGS + WS client.
7. `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosListRecentTool.kt` — first tool, the pattern to follow.
8. `chat-service/src/main/java/com/agentplatform/chat/service/MemoryService.java` — pgvector RAG (curated first + access_count tracking).
9. `agent-service/src/main/java/com/agentplatform/agent/ai/SemanticPhotoSearchCallback.java` + Android `PhotosSemanticCandidatesTool.kt` — semantic photo search.
10. `agent-service/src/main/java/com/agentplatform/agent/ai/{LangChain4jModelFactory,RoutingBackgroundChatModel,BackgroundFactExtractor,SessionSummarizer,BackgroundLlmClient,EmbeddingService}.java` — LangChain4j background LLM + embedding integration.
11. `docker-compose.yml` + `docker-compose.ghcr.yml` + `infra/postgres/init/02-pgvector.sh` + `infra/caddy/Caddyfile` — deployment.

## Canonical local build workflow

Default to the local build environment. Do not use Docker as the build runner for Java, Web, or Android unless the user explicitly asks for containerized builds. Docker is for runtime/deployment packaging after local build succeeds.

PowerShell from `D:\agent-platform`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd clean package -DskipTests
```

Targeted Java tests:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd "-pl" "agent-service" "-am" "-Dtest=PhotoToolArgsSanitizerTest,SemanticPhotoSearchFormattingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Web build from `D:\agent-platform\web`:

```powershell
npm run build
```

Android APK build from `D:\agent-platform\android`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:ANDROID_HOME = 'C:\Users\admin\AppData\Local\Android\Sdk'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat assembleDebug
```

Web at `http://localhost` (or whatever `WEB_PUBLIC_URL` is); first user registers via UI; create a device enrollment from the Devices page; install `app-debug.apk` on Android, scan QR, grant notification + media permissions.

## Validation checklist

- Before deployment or image builds, run `git status --short` and keep the worktree clean. If there are pending changes, either commit them in a named branch or intentionally stash/park them before building, so a deploy never accidentally includes unrelated dirty worktree changes.
- If GitNexus itself has a problem (MCP/CLI call fails, tool is unavailable, index is stale/broken, or impact/change detection cannot run), stop the current business task and fix GitNexus first. Run `npx gitnexus analyze` when needed, repair/restart the MCP/CLI path if needed, and verify GitNexus works before continuing code, deploy, or debugging work.
- Before editing code symbols, follow `AGENTS.md`: run GitNexus impact analysis and warn if risk is HIGH/CRITICAL. Before committing, run `gitnexus_detect_changes()`.
- Java changes: use the local Maven wrapper `.\mvnw.cmd` from the repository root with JDK 21. Do not use Gradle for backend services; this is a Maven multi-module backend.
- Web changes: run `npm run build` in `web/`. When online deployment is available, deploy immediately after a successful build and verify the live web asset hash/container image. Do not stop at "build passed" unless the user explicitly asked for local-only validation.
- Android ADB path on this machine is `C:\Users\admin\AppData\Local\Android\Sdk\platform-tools\adb.exe`; `adb` is not necessarily on PATH.
- After installing the Android APK via ADB, always grant/check Agent Platform permissions before handing back. Grant runtime permissions with `pm grant com.agentplatform.android android.permission.POST_NOTIFICATIONS`, `CAMERA`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, and `READ_MEDIA_VISUAL_USER_SELECTED`; set relevant appops to `allow` for `POST_NOTIFICATION`, `CAMERA`, media reads, `SYSTEM_ALERT_WINDOW`, `USE_FULL_SCREEN_INTENT`, `START_FOREGROUND`, background run ops, and `WAKE_LOCK`; whitelist battery with `dumpsys deviceidle whitelist +com.agentplatform.android`. Then confirm/enable accessibility (`com.agentplatform.android/com.agentplatform.android.ui.accessibility.UiAccessibilityService`), preserving existing enabled services, and verify with `settings get secure enabled_accessibility_services`, `dumpsys package com.agentplatform.android`, and `appops get com.agentplatform.android`.
- For Xiaomi/HyperOS background-launch blocks, grant standard appops plus MIUI private numeric ops for `com.agentplatform.android`: `10004 10017 10018 10020 10021 10022 10045`. Do not use the displayed names like `MIUIOP(10021)` in `appops set`; they are only display labels and Android rejects them.
- Android builds should use process-local `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` and `ANDROID_HOME=C:\Users\admin\AppData\Local\Android\Sdk`. Do not use `C:\Users\admin\.jdks\openjdk-26`; Gradle/AGP fails with a bare `* What went wrong: 26`. The older `C:\Users\admin\.jdks\ms-21.0.10` path may not exist on this machine.
- The Android Gradle wrapper lives under `android/`, not the repository root. Run `.\gradlew.bat assembleDebug` with `workdir=D:\agent-platform\android`.
- After changing accessibility service config or UI tools, install with `adb install -r -d -t android/app/build/outputs/apk/debug/app-debug.apk`, start `com.agentplatform.android/.ui.MainActivity`, then verify `settings get secure enabled_accessibility_services`, `settings get secure accessibility_enabled`, and `dumpsys accessibility` show `UiAccessibilityService` bound. If a tool manifest changed, restart the app/foreground service so the phone re-registers tools over WebSocket.
- APK install preference: first check whether the user says they are local with USB/ADB or outside. If a device is connected through ADB, install with ADB and enable accessibility; do not publish or provide the APK download URL. If the user says they are outside / have no ADB, copy `android/app/build/outputs/apk/debug/app-debug.apk` to `build/apk-local-server/agent-platform-debug.apk`, run or reuse `python -m http.server 53095 --bind 0.0.0.0` from `build/apk-local-server`, and provide `http://home.rainaki.top:53095/agent-platform-debug.apk`.
- Mobile voice/local model direction was removed at the user's request. Do not re-add Qwen/Gemma on-device LLM routing, model download UI, microphone permission, or Voice Agent UI unless the user explicitly asks to bring that feature back.
- For quote-heavy Android shell edits, pipe a script into `adb shell run-as com.agentplatform.android sh`; PowerShell plus inline `adb shell ... sh -c` quoting is fragile and can corrupt commands.
- Agent-service targeted tests from Windows:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd "-pl" "agent-service" "-am" "-Dtest=PhotoToolArgsSanitizerTest,SemanticPhotoSearchFormattingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

- Treat `Dockerfile.spring` and `web/Dockerfile` as container packaging references, not the canonical build path. They run Maven/pnpm inside Docker and can re-download dependencies or fail on image/network issues. For normal work, build locally first.
- `git diff --check` is useful, but CRLF warnings in unrelated files may be pre-existing. Fix only files touched for the current task.

## Preferred local-build deploy flow

Always build/test locally first. If deployment needs GHCR, package already-built `target/*.jar` or `web/dist` artifacts into an image. Do not use Docker as the dependency-resolving build environment.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd "-pl" "common,auth-service,agent-service,chat-service,gateway,device-hub-service" "-am" -DskipTests package
Push-Location web
npm run build
Pop-Location
```

### Web-only deploy from local `dist`

Use this when only React/web changed. This avoids the old `web/Dockerfile` path, so Docker does not run `pnpm install` or `pnpm build` again. `web/.dockerignore` excludes `dist`, so build from a temporary context that contains only `dist`, `nginx.conf`, and a tiny Dockerfile.

```powershell
$tag = "web-$(Get-Date -Format yyyyMMdd-HHmm)-$(git rev-parse --short HEAD)"
Push-Location web
npm run build
Pop-Location

$ctx = Join-Path $env:TEMP "agent-platform-web-dist-$tag"
if (Test-Path $ctx) { Remove-Item -LiteralPath $ctx -Recurse -Force }
New-Item -ItemType Directory -Path $ctx | Out-Null
Copy-Item -Path 'D:\agent-platform\web\dist' -Destination (Join-Path $ctx 'dist') -Recurse
Copy-Item -Path 'D:\agent-platform\web\nginx.conf' -Destination (Join-Path $ctx 'nginx.conf')
@'
FROM nginx:alpine
COPY dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
'@ | Set-Content -Path (Join-Path $ctx 'Dockerfile') -Encoding ASCII

$image = "ghcr.io/yizhiakuya/agent-platform-web:$tag"
docker build -t $image $ctx
docker push $image
```

Then update Megumin and verify. Use `--profile default`; without it, compose may report `no such service: web`.

```powershell
$image = 'ghcr.io/yizhiakuya/agent-platform-web:<tag>'
$remote = @'
set -eu
cd /opt/agent-platform
new_image='__IMAGE__'
cp docker-compose.ghcr-photo.yml "docker-compose.ghcr-photo.yml.bak-web-$(date +%Y%m%d-%H%M%S)"
python3 - <<'PY'
from pathlib import Path
p = Path('docker-compose.ghcr-photo.yml')
text = p.read_text()
new = '__IMAGE__'
out = []
in_web = False
for line in text.splitlines():
    stripped = line.strip()
    if line.startswith('  ') and not line.startswith('    ') and stripped.endswith(':'):
        in_web = stripped == 'web:'
    if in_web and stripped.startswith('image:'):
        indent = line[:len(line) - len(line.lstrip())]
        out.append(f'{indent}image: {new}')
    else:
        out.append(line)
p.write_text('\n'.join(out) + '\n')
PY
grep -n "$new_image" docker-compose.ghcr-photo.yml
docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr-photo.yml pull web
docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr-photo.yml up -d --no-build --force-recreate web
curl -fsSI http://localhost:3000/ | sed -n '1,10p'
docker inspect -f '{{.Name}} {{.Config.Image}} {{.State.Status}}' agent-platform-web
'@
$remote = $remote.Replace('__IMAGE__', $image)
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
ssh root@192.168.0.109 "echo $b64 | base64 -d | bash"
```

Megumin deploy shortcut when remote compose has only local image names:

```bash
cd /opt/agent-platform
docker pull ghcr.io/yizhiakuya/agent-platform-agent-service:<tag>
docker pull ghcr.io/yizhiakuya/agent-platform-web:<tag>
docker tag ghcr.io/yizhiakuya/agent-platform-agent-service:<tag> agent-platform-agent-service:latest
docker tag ghcr.io/yizhiakuya/agent-platform-web:<tag> agent-platform-web:latest
docker compose --profile default up -d --no-build --force-recreate agent-service web
docker exec agent-platform-agent curl -fsS http://localhost:8082/actuator/health
curl -fsSI http://localhost:3000/ | head
```

Megumin deploy shortcut when using `/opt/agent-platform/docker-compose.ghcr-photo.yml`:

```powershell
$old = 'ghcr.io/yizhiakuya/agent-platform-agent-service:old-tag'
$new = 'ghcr.io/yizhiakuya/agent-platform-agent-service:new-tag'
ssh root@192.168.0.109 "cd /opt/agent-platform && set -e && sed -i 's#$old#$new#g' docker-compose.ghcr-photo.yml && grep -n '$new' docker-compose.ghcr-photo.yml && docker compose -f docker-compose.yml -f docker-compose.ghcr-photo.yml pull agent-service && docker compose -f docker-compose.yml -f docker-compose.ghcr-photo.yml up -d --no-build --force-recreate agent-service && docker exec agent-platform-agent curl -fsS http://localhost:8082/actuator/health"
```

If the remote command needs `$(...)`, `$VAR`, heredocs, or embedded quotes, avoid inline SSH strings. Base64-encode a small remote script or upload a temp script, run it with `set -e`, and verify the compose file before restarting.

## Stage 2 / 3 roadmap (not yet done)

- Real provider failover via reactor `onErrorResume` chain (current sync try-catch only catches setup-phase).
- Embedding provider config independent of chat provider.
- `Codex-haiku` fact extractor on its own ChatClient bean (currently shares first provider).
- Session JSONL trajectory + soft-delete reset (currently messages-only).
- Multi-device alias normalization (`phone1.photos.list_recent`).
- Vendor white-listing helpers for Chinese ROMs (Xiaomi/Huawei) battery optimization.
- Multi-channel (TG / WeChat / iOS) entry abstraction.

## Reference designs we borrowed from

- **Codex**: memory tiering (resident MEMORY.md + on-demand sub-files), skills routing via description, subagent context isolation.
- **OpenClaw**: workspace 7-markdown split (SOUL/IDENTITY/USER/AGENTS/TOOLS/MEMORY/HEARTBEAT), daily memory journals + LanceDB vectors. We adopted the markdown split; daily journals + LanceDB are TODO.
