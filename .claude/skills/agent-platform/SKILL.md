---
name: agent-platform
description: Self-hosted mobile agent platform — Java 21 + Spring Boot 3.5 + Spring Cloud + Spring AI + React + Kotlin Compose Android. LLM drives tools running on the user's bound Android phone via WebSocket. Use this skill when the user asks about the architecture, wants to add a new device tool, modify LLM prompt assembly (persona/skills/memory), debug WS dispatch, change DB schema, build the APK, or deploy with docker-compose.
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

## Adding a new device tool (the canonical workflow)

1. Implement `com.agentplatform.android.core.tool.Tool` in `android/app/src/main/java/com/agentplatform/android/tools/<domain>/<Name>Tool.kt` — see `PhotosListRecentTool.kt` as a reference. `name` uses dot namespace (`<domain>.<action>`); `schema` is a JSON Schema object the LLM sees; `description` should be specific enough that the LLM picks it correctly.
2. Register in `AgentForegroundService.onCreate`: `toolRegistry.register(YourTool(applicationContext, mapper))`.
3. Add any runtime permissions to `AndroidManifest.xml` and (if Android 13+ runtime grant required) wire a `PermissionCard` in `BoundScreen.kt`.
4. `./gradlew assembleDebug` in `android/`. Reinstall APK; on next reconnect the device pushes its updated tool manifest, and the LLM sees the new tool on the very next chat.

**Server-side: zero changes needed.** That's the headline feature of the platform.

## Adding a new skill (LLM playbook)

1. Create `agent-service/src/main/resources/skills/<slug>/SKILL.md` with YAML frontmatter `name` + `description`. The body is a playbook the LLM loads on demand via the `skill_load` meta-tool.
2. Rebuild `agent-service` and redeploy. `SkillRegistry` rescans classpath on startup.

## Modifying LLM behavior (persona)

The system prompt is assembled in `ChatService.handleWithLlm` from 4 markdowns plus the user's `user_preferences` row plus a memory recall block:

```
# IDENTITY        (persona/IDENTITY.md — who the agent is)
# SOUL            (persona/SOUL.md — behavioral principles)
# AGENTS          (persona/AGENTS.md — startup order, red lines, decision trees)
# TOOLS           (persona/TOOLS.md — environment-specific tool conventions)
# USER            (auth.user_preferences.content — user's CLAUDE.md analogue)
# AVAILABLE SKILLS (skill names + descriptions; bodies loaded via skill_load)
# RELEVANT MEMORIES (UNTRUSTED) (top-K from memory_facts, curated tier first)
```

Edit the relevant `.md` file and redeploy `agent-service`.

## Memory (`chat.memory_facts` + `chat.memory_embeddings`)

- `MemoryExtractor` (in agent-service) runs after each chat turn: a cheap LLM extracts facts/preferences from the user/assistant exchange, embeds them via OpenAI-compatible `/v1/embeddings`, and writes via Feign to chat-service `POST /internal/memory/facts`.
- On the next turn, `recallMemories` queries `POST /internal/memory/facts/query` (pgvector cosine, two-stage: curated first, then raw).
- `POST /internal/memory/promote` periodically elevates frequently-hit raw facts to `is_curated=true`.

## Tool hooks

`RemoteToolCallback.call` runs:
1. `List<ToolPreInterceptor>` synchronously — interceptors may rewrite `args` or throw `ToolBlockedException`.
2. `dispatcher.dispatch(...)` (HTTP DeferredResult into device-hub).
3. `ApplicationEventPublisher.publishEvent(new ToolPostEvent(...))` for async audit/metrics.

Add a new interceptor by creating a `@Component implements ToolPreInterceptor` — Spring auto-collects.

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

## Critical files (read these first when picking up the project)

1. `agent-service/src/main/java/com/agentplatform/agent/chat/ChatService.java` — prompt assembly + LLM stream + memory recall + B2 fact extraction trigger.
2. `agent-service/src/main/resources/persona/{SOUL,IDENTITY,AGENTS,TOOLS}.md` — agent's "soul" (4 markdowns).
3. `agent-service/src/main/java/com/agentplatform/agent/ai/RemoteToolCallback.java` — tool dispatch core (hooks, b64 strip).
4. `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java` — JSON-RPC routing.
5. `device-hub-service/src/main/java/com/agentplatform/hub/config/HubBeans.java` — WS 4MB buffer.
6. `android/app/src/main/java/com/agentplatform/android/service/AgentForegroundService.kt` — Android FGS + WS client.
7. `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosListRecentTool.kt` — first tool, the pattern to follow.
8. `chat-service/src/main/java/com/agentplatform/chat/service/MemoryService.java` — pgvector RAG (curated first + access_count tracking).
9. `docker-compose.yml` + `infra/postgres/init/02-pgvector.sh` + `infra/caddy/Caddyfile` — deployment.

## Local dev quick start

```bash
cp .env.example .env  # fill in ANTHROPIC_API_KEY, JWT_SECRET (openssl rand -base64 64), DB passwords
./mvnw clean package -DskipTests
docker compose --profile default up -d --build
```

Web at `http://localhost` (or whatever `WEB_PUBLIC_URL` is); first user registers via UI; create a device enrollment from the Devices page; install `app-debug.apk` on Android, scan QR, grant notification + media permissions.

## Stage 2 / 3 roadmap (not yet done)

- Real provider failover via reactor `onErrorResume` chain (current sync try-catch only catches setup-phase).
- Embedding provider config independent of chat provider.
- `claude-haiku` fact extractor on its own ChatClient bean (currently shares first provider).
- Session JSONL trajectory + soft-delete reset (currently messages-only).
- Multi-device alias normalization (`phone1.photos.list_recent`).
- Vendor white-listing helpers for Chinese ROMs (Xiaomi/Huawei) battery optimization.
- Multi-channel (TG / WeChat / iOS) entry abstraction.

## Reference designs we borrowed from

- **Claude Code**: memory tiering (resident MEMORY.md + on-demand sub-files), skills routing via description, subagent context isolation.
- **OpenClaw**: workspace 7-markdown split (SOUL/IDENTITY/USER/AGENTS/TOOLS/MEMORY/HEARTBEAT), daily memory journals + LanceDB vectors. We adopted the markdown split; daily journals + LanceDB are TODO.
