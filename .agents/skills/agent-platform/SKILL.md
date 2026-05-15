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
| GHCR / megumin deploy | local Docker build + push, then `ssh root@192.168.0.109` and `/opt/agent-platform` |

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

- `MemoryExtractor` (in agent-service) runs after each chat turn: a cheap LLM extracts facts/preferences from the user/assistant exchange, embeds them via OpenAI-compatible `/v1/embeddings`, and writes via Feign to chat-service `POST /internal/memory/facts`.
- On the next turn, `recallMemories` queries `POST /internal/memory/facts/query` (pgvector cosine, two-stage: curated first, then raw).
- `POST /internal/memory/promote` periodically elevates frequently-hit raw facts to `is_curated=true`.

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
10. `docker-compose.yml` + `docker-compose.ghcr.yml` + `infra/postgres/init/02-pgvector.sh` + `infra/caddy/Caddyfile` — deployment.

## Local dev quick start

```bash
cp .env.example .env  # fill in ANTHROPIC_API_KEY, JWT_SECRET (openssl rand -base64 64), DB passwords
./mvnw clean package -DskipTests
docker compose --profile default up -d --build
```

Web at `http://localhost` (or whatever `WEB_PUBLIC_URL` is); first user registers via UI; create a device enrollment from the Devices page; install `app-debug.apk` on Android, scan QR, grant notification + media permissions.

## Validation checklist

- Before deployment or image builds, run `git status --short` and keep the worktree clean. If there are pending changes, either commit them in a named branch or intentionally stash/park them before building, so a deploy never accidentally includes unrelated dirty worktree changes.
- If GitNexus itself has a problem (MCP/CLI call fails, tool is unavailable, index is stale/broken, or impact/change detection cannot run), stop the current business task and fix GitNexus first. Run `npx gitnexus analyze` when needed, repair/restart the MCP/CLI path if needed, and verify GitNexus works before continuing code, deploy, or debugging work.
- Before editing code symbols, follow `AGENTS.md`: run GitNexus impact analysis and warn if risk is HIGH/CRITICAL. Before committing, run `gitnexus_detect_changes()`.
- Web changes: run `npm run build` in `web/`. When online deployment is available, prefer deploying and testing the live web environment directly after a successful build; use local mocked previews only when live validation is unavailable or risk isolation is needed.
- Android ADB path on this machine is `C:\Users\admin\AppData\Local\Android\Sdk\platform-tools\adb.exe`; `adb` is not necessarily on PATH.
- After installing the Android APK via ADB, always grant/check Agent Platform permissions before handing back. Grant runtime permissions with `pm grant com.agentplatform.android android.permission.POST_NOTIFICATIONS`, `CAMERA`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, and `READ_MEDIA_VISUAL_USER_SELECTED`; set relevant appops to `allow` for `POST_NOTIFICATION`, `CAMERA`, media reads, `SYSTEM_ALERT_WINDOW`, `USE_FULL_SCREEN_INTENT`, `START_FOREGROUND`, background run ops, and `WAKE_LOCK`; whitelist battery with `dumpsys deviceidle whitelist +com.agentplatform.android`. Then confirm/enable accessibility (`com.agentplatform.android/com.agentplatform.android.ui.accessibility.UiAccessibilityService`), preserving existing enabled services, and verify with `settings get secure enabled_accessibility_services`, `dumpsys package com.agentplatform.android`, and `appops get com.agentplatform.android`.
- For Xiaomi/HyperOS background-launch blocks, grant standard appops plus MIUI private numeric ops for `com.agentplatform.android`: `10004 10017 10018 10020 10021 10022 10045`. Do not use the displayed names like `MIUIOP(10021)` in `appops set`; they are only display labels and Android rejects them.
- Android builds should use process-local `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` and `ANDROID_HOME=C:\Users\admin\AppData\Local\Android\Sdk`. Do not use `C:\Users\admin\.jdks\openjdk-26`; Gradle/AGP fails with a bare `* What went wrong: 26`. The older `C:\Users\admin\.jdks\ms-21.0.10` path may not exist on this machine.
- The Android Gradle wrapper lives under `android/`, not the repository root. Run `.\gradlew.bat assembleDebug` with `workdir=D:\agent-platform\android`.
- After changing accessibility service config or UI tools, install with `adb install -r -d -t android/app/build/outputs/apk/debug/app-debug.apk`, start `com.agentplatform.android/.ui.MainActivity`, then verify `settings get secure enabled_accessibility_services`, `settings get secure accessibility_enabled`, and `dumpsys accessibility` show `UiAccessibilityService` bound. If a tool manifest changed, restart the app/foreground service so the phone re-registers tools over WebSocket.
- APK install preference: first check whether the user says they are local with USB/ADB or outside. If a device is connected through ADB, install with ADB and enable accessibility; do not publish or provide the APK download URL. If the user says they are outside / have no ADB, copy `android/app/build/outputs/apk/debug/app-debug.apk` to `build/apk-local-server/agent-platform-debug.apk`, run or reuse `python -m http.server 53095 --bind 0.0.0.0` from `build/apk-local-server`, and provide `http://home.rainaki.top:53095/agent-platform-debug.apk`.
- Mobile voice/local model direction was removed at the user's request. Do not re-add Qwen/Gemma on-device LLM routing, model download UI, microphone permission, or Voice Agent UI unless the user explicitly asks to bring that feature back.
- For quote-heavy Android shell edits, pipe a script into `adb shell run-as com.agentplatform.android sh`; PowerShell plus inline `adb shell ... sh -c` quoting is fragile and can corrupt commands.
- Agent-service targeted tests from Windows/Docker:

```powershell
docker run --rm -v ${PWD}:/workspace -v ${env:USERPROFILE}/.m2:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-21 sh -lc './mvnw -pl agent-service -am "-Dtest=PhotoToolArgsSanitizerTest,SemanticPhotoSearchFormattingTest" -Dsurefire.failIfNoSpecifiedTests=false test'
```

- Full Java packaging can use `Dockerfile.spring`; Android compile needs local Android SDK or an Android SDK image.
- `git diff --check` is useful, but CRLF warnings in unrelated files may be pre-existing. Fix only files touched for the current task.

## Preferred GHCR deploy flow

The user prefers local Docker builds, GHCR push, then server pull/update. If local Docker is stopped, tell the user to start Docker instead of falling back to slow server builds.

```powershell
docker build --build-arg SERVICE=agent-service -f Dockerfile.spring -t agent-platform-agent-service:latest .
docker build -t agent-platform-web:latest ./web
docker tag agent-platform-agent-service:latest ghcr.io/yizhiakuya/agent-platform-agent-service:<tag>
docker tag agent-platform-web:latest ghcr.io/yizhiakuya/agent-platform-web:<tag>
docker push ghcr.io/yizhiakuya/agent-platform-agent-service:<tag>
docker push ghcr.io/yizhiakuya/agent-platform-web:<tag>
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
