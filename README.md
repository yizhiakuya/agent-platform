# Agent Platform

Self-hosted Android agent platform. Run the server stack on your own machine,
install the Android app on your phone, then chat with an LLM agent from the web
UI while it drives phone-side tools over WebSocket.

The current product direction is server-side LLM orchestration plus Android
device tools. Mobile voice entry, XiaoAI integration, and on-device local LLM
experiments are intentionally not part of the active codebase.

## Architecture

```text
Web UI (React + SSE)
        |
        v
Spring Cloud Gateway -- auth-service
        |
        v
agent-service -- chat-service (sessions, messages, memory, photo index)
        |
        v
device-hub-service -- WebSocket / JSON-RPC -- Android foreground service
```

Tools are dynamic: each Android client sends a `tool.manifest` after it
connects. `agent-service` injects those tools into the model request for that
chat turn. Adding a phone capability is normally an Android `Tool` class plus
registration in `AgentForegroundService`; the server does not need a matching
endpoint for every device tool.

## Stack

| Layer | Choice |
| --- | --- |
| Server | Java 21, Spring Boot 3.5.6, Spring Cloud 2025.0.0, Spring Cloud Alibaba 2025.0.0.0 |
| LLM | Configurable provider pool: Codex Responses-compatible endpoint first, Anthropic Messages fallback |
| DB | PostgreSQL 16 + pgvector, schema-per-service (`auth`, `chat`, `hub`) |
| Device realtime | JSON-RPC 2.0 over WebSocket |
| Frontend | React, Vite, TypeScript, Tailwind, TanStack Query |
| Android | Kotlin, Jetpack Compose, OkHttp, foreground service, accessibility/screen tools |
| Deploy | Docker Compose, GHCR images, optional local photo embedding sidecar |

## Prerequisites

- Docker 24+
- JDK 21
- Node 20 + pnpm 9 when building the web UI outside Docker
- Android Studio or a configured Android SDK when building the APK
- At least one usable LLM provider key in `.env`

## Quick Start

```bash
cp .env.example .env
# Fill JWT_SECRET, database passwords, and at least one of:
#   CODEX_API_KEY or OPENAI_API_KEY for the Codex Responses-compatible provider
#   ANTHROPIC_API_KEY for the Anthropic fallback provider

docker compose up -d
docker compose ps
open http://localhost:3000
```

Then register an account, create a device enrollment from the Devices page,
install the Android APK, and scan the QR code or paste the token and server URL
on the bind screen.

## Android App

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first launch asks for the server URL and enrollment token. The bound app
runs a foreground service that connects to `/ws/device` and exposes registered
tools such as photos and UI automation.

## LLM And Embeddings

`agent-service` reads providers from `agent-platform.agent.providers`:

- Codex Responses-compatible endpoint: `CODEX_API_KEY`, `CODEX_BASE_URL`,
  `CODEX_CHAT_MODEL`
- Anthropic Messages endpoint: `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL`,
  `LLM_CHAT_MODEL`

Blank or placeholder keys are skipped. If every provider is blank,
`agent-service` fails fast instead of silently switching to a fake model.

Memory and semantic photo search use embedding endpoints:

- `MEMORY_EMBEDDING_*` for long-term memory recall and storage
- `PHOTO_EMBEDDING_*` for multimodal photo indexing
- `photo-embedding-sidecar` is optional and runs only with the `photo-local`
  Compose profile

## Deploy From GHCR

Images are published by `.github/workflows/publish-ghcr.yml` on pushes to
`main`, version tags, and manual workflow dispatch.

```bash
echo "$GHCR_TOKEN" | docker login ghcr.io -u yizhiakuya --password-stdin
docker compose -f docker-compose.yml -f docker-compose.ghcr.yml pull
docker compose -f docker-compose.yml -f docker-compose.ghcr.yml up -d --no-build
```

Published image names use:

```text
ghcr.io/yizhiakuya/agent-platform-auth-service
ghcr.io/yizhiakuya/agent-platform-gateway
ghcr.io/yizhiakuya/agent-platform-device-hub-service
ghcr.io/yizhiakuya/agent-platform-agent-service
ghcr.io/yizhiakuya/agent-platform-chat-service
ghcr.io/yizhiakuya/agent-platform-web
```

## Local Development

```bash
docker compose up -d postgres nacos
# Run Spring services from the IDE with the same env values as .env.
cd web && pnpm install && pnpm dev
```

The Vite dev server proxies API calls to the gateway.

## Smoke Checks

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8082/actuator/health
curl -fsS http://localhost:8083/actuator/health
curl -fsS http://localhost:8084/actuator/health
```

For an end-to-end agent check, use the web UI with a configured LLM provider and
a bound Android device. Device-free mock sessions are limited to hub tests and
development of `/internal/tools/call`; they are not a replacement for the chat
agent path.

## Repo Layout

```text
.
|-- pom.xml
|-- docker-compose.yml
|-- docker-compose.ghcr.yml
|-- Dockerfile.spring
|-- common/
|   |-- common-api/
|   |-- common-protocol/
|   `-- common-security/
|-- auth-service/
|-- gateway/
|-- device-hub-service/
|-- agent-service/
|-- chat-service/
|-- web/
|-- android/
|-- photo-embedding-sidecar/
|-- infra/
`-- docs/
```

## Known Gaps

| Item | Status |
| --- | --- |
| Multi-instance device hub | Not yet; production assumes one hub instance or sticky routing |
| FCM push wakeup | Not yet; Android uses a foreground service and WebSocket reconnect |
| Encrypted device token storage | Still plain SharedPreferences |
| End-to-end Playwright + adb suite | Partial test coverage only |
| Provider failover mid-stream | Setup-stage failover exists; stream-stage failover is not implemented |
