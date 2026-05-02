# Agent Platform

Self-host mobile agent platform. Run the server stack with Docker on your own
machine; install the Android app on your phone; chat with an LLM agent from a
Web UI (Telegram / CLI to come) that can drive your phone — read photos, save
files, manage notifications, etc.

**Status**: MVP phase 1 — all 14 PRs landed. End-to-end runnable with
`docker compose up` (mock mode) or with a real Android device + Anthropic API
key.

---

## Architecture (one screenful)

```
       Web (React + Vite + SSE)        Telegram bot       CLI
                       ╲                   │                ╱
                        ╲                  │               ╱
                         ▼                 ▼              ▼
                    ┌──────────────── Gateway ────────────────┐
                    │  Spring Cloud Gateway · JWT filter      │
                    │  CORS · WS routing · X-Forwarded headers│
                    └──────────────┬──────────────────────────┘
                                   │
            ┌──────────────────────┼─────────────────────┐
            ▼                      ▼                     ▼
       ┌─────────┐         ┌────────────┐       ┌────────────────┐
       │  auth   │  ◀──    │  agent     │  ──▶  │ device-hub     │
       │ service │  Feign  │  service   │  WebClient (lb)         │
       │         │         │ (Spring AI │       │  WebSocket hub  │
       │ users/  │         │  ChatClient│       │  per-device     │
       │ devices │         │  +tools)   │       │  JSON-RPC 2.0   │
       │ JWT     │         └─────┬──────┘       └────────┬───────┘
       └─────────┘               │ Feign                 │ wss
                                 ▼                        │
                          ┌────────────┐                  │
                          │ chat-svc   │                  │
                          │ session +  │                  │
                          │ message +  │                  ▼
                          │ PII filter │           ┌────────────┐
                          └────────────┘           │  Android   │
                                                   │ ForeService│
                                                   │ Tool host  │
                                                   └────────────┘
```

Tools are dynamic: each Android client posts a `tool.manifest` on connect,
agent-service injects them into Spring AI's function-calling list per chat
request. Adding a new capability = a new `Tool` class on Android, **server
unchanged**.

---

## Stack (locked)

| Layer | Choice |
|-------|--------|
| Server | Java 21 · Spring Boot 3.5.6 · Spring Cloud 2025.0.0 (Northfields) · Spring Cloud Alibaba 2025.0.0.0 · Spring AI 1.0.6 (Anthropic) |
| DB | PostgreSQL 16 — single DB, schema-per-service (`auth` / `chat` / `hub`) |
| Realtime | raw JSON-RPC 2.0 over WebSocket |
| Frontend | React + Vite + TypeScript + Tailwind + TanStack Query |
| Android | Kotlin + Jetpack Compose + OkHttp + Foreground Service (`specialUse`) |
| Build | Maven multi-module + `./mvnw` ; Gradle for Android |
| Deploy | Docker Compose · multi-stage Alpine images |

See [the plan file](C:\Users\admin\.claude\plans\rippling-crafting-mochi.md) for
detailed design decisions.

---

## Prerequisites

- **Docker** ≥ 24
- **JDK 21** (Eclipse Temurin recommended; the bundled JBR in Android Studio works for dev too)
- **Node 20** + **pnpm 9** (only needed if you build the web outside Docker)
- **Android Studio** (only for building the APK)

The project ships a Maven Wrapper (`./mvnw`) — no global Maven required.

---

## Quick start (everything in Docker)

```bash
# 1. Configure secrets
cp .env.example .env
# Edit .env:
#   - JWT_SECRET            (openssl rand -base64 64)
#   - NACOS_AUTH_TOKEN      (openssl rand -base64 32, only if NACOS_AUTH_ENABLE=true)
#   - ANTHROPIC_API_KEY     (sk-ant-... — leave as PLACEHOLDER for mock-LLM mode)
#   - POSTGRES_PASSWORD, AUTH_DB_PASSWORD, CHAT_DB_PASSWORD, HUB_DB_PASSWORD

# 2. Bring up the full stack
docker compose up -d
docker compose ps    # 8 containers should reach 'healthy' within ~60s

# 3. Open the web UI
open http://localhost:3000

# 4. Register an account in the UI → login → go to Devices → "New enrollment"
# 5. Install the Android APK (see "Building the Android app" below) and either
#    scan the QR or paste the token + server URL on the bind screen
# 6. Back in the web UI's chat page, type "show me my recent photos" — the LLM
#    (or the mock LLM if no API key) will call photos.list_recent on your phone
```

### Dev workflow (infra in Docker, services in IDE)

```bash
# Start ONLY postgres + nacos
docker compose up -d postgres nacos

# Run the spring services from IntelliJ IDEA (one Run config each).
# Set env: AUTH_DB_PASSWORD / CHAT_DB_PASSWORD / JWT_SECRET / ANTHROPIC_API_KEY
# Then run the web frontend with:
cd web && pnpm install && pnpm dev    # vite proxies /api → http://localhost:8080
```

---

## Building the Android app

```bash
cd android
# Open this directory in Android Studio. It will sync Gradle and download SDKs.
# Then: Run > Run 'app' on your connected device, OR
./gradlew :app:assembleDebug          # produces app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch the app prompts for the server URL and enrollment token. Generate
the token in the web UI's **Devices → New enrollment** flow.

---

## Smoke test (works with no Anthropic key, no Android device)

```bash
# Login
TOKEN=$(curl -sS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<password>"}' | jq -r .token)

# Stream a chat — mock-LLM path auto-provisions a fake device
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"message\":\"show me my photos\",\"deviceId\":\"$(uuidgen)\"}"

# Expect to see (in order): user_message → tool_call_started → tool_call_result
# → assistant_message events on the SSE stream.
```

---

## Repo layout

```
.
├── pom.xml                      # parent (Spring Boot/Cloud/AI BOM)
├── mvnw / mvnw.cmd              # Maven Wrapper
├── docker-compose.yml           # full stack
├── Dockerfile.spring            # universal Dockerfile for all 5 spring services
├── .env.example                 # config template
├── infra/
│   ├── postgres/init/01-init.sh # creates per-service schemas + roles
│   └── nacos/                   # placeholder for future Nacos custom config
├── common/
│   ├── common-protocol/         # JSON-RPC 2.0 messages + ToolSpec/ToolResult
│   ├── common-security/         # JwtUtil, Principal, auth filters
│   └── common-api/              # cross-service DTOs (hub.*, chat.*)
├── auth-service/                # users, JWT, device enrollment
├── gateway/                     # Spring Cloud Gateway
├── device-hub-service/          # WS hub + DeferredResult tool dispatch
├── agent-service/               # Spring AI ChatClient + RemoteToolCallback
├── chat-service/                # session + message persistence
├── web/                         # React UI (Vite + Tailwind)
└── android/                     # Kotlin + Compose tool host
```

---

## Mock-LLM mode (default — no API key needed)

Set `ANTHROPIC_API_KEY=` (empty) or leave the placeholder. agent-service detects
this and replaces `ChatClient` with a mock that emits a fixed sequence:
`user_message → tool_call_started(photos.list_recent) → tool_call_result →
assistant_message`. The mock device-hub auto-provisions a fake `MockDeviceSession`
when it sees an unknown deviceId, returning fake `echoed_params` JSON 100ms
later. Lets you exercise the full chat path with zero Android setup and zero
LLM bill.

To switch to real Anthropic Claude:
1. Put a real `sk-ant-...` key into `.env`'s `ANTHROPIC_API_KEY`
2. `docker compose restart agent-service`

agent-service log will say `Spring AI ChatClient ready (model=claude-sonnet-4-6)`.

---

## Observability (PR 13)

agent-service exposes Prometheus metrics at `/actuator/prometheus`. Other
services have `/actuator/health` and `/actuator/info` enabled by default. To
roll out Prometheus on more services, add to each service's pom:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <scope>runtime</scope>
</dependency>
```

and `prometheus,metrics` to that service's `management.endpoints.web.exposure.include`.

---

## Hardening / known gaps (future PRs)

| Item | Status |
|---|---|
| HMAC command signing | Cut (TLS+JWT covers the threat model — see plan §1.5) |
| jti revocation list | ✅ DB table; gateway local-verify path doesn't consult it yet (PR 13) |
| Nacos auth | Disabled in dev; enable via `NACOS_AUTH_ENABLE=true` after manual UI bootstrap |
| Multi-instance device-hub (sticky/Redis pub-sub) | Not yet — single hub assumption |
| Vendor-specific Android battery whitelist | XXPermissions hook stubbed — wire concrete intents in PR 13.5 |
| FCM push wakeup | Not yet — `pingInterval=30s` Foreground Service for now |
| EncryptedSharedPreferences for device JWT | Plain SharedPreferences in PR 10; switch in PR 13.5 |
| End-to-end Playwright + adb suite | Skeleton in repo, full coverage TBD |
