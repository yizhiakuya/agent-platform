---
name: ws
description: "Skill for the Ws area of agent-platform. 40 symbols across 19 files."
---

# Ws

40 symbols | 19 files | Cohesion: 67%

## When to Use

- Working with code in `device-hub-service/`
- Understanding how TokenAwareHandshakeHandler, isOpen, send work
- Modifying ws-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `device-hub-service/src/test/java/com/agentplatform/hub/ws/DeviceWebSocketIntegrationTest.java` | bearerHeader, wsEndpoint, invalid_token_rejects_handshake, no_bearer_subprotocol_rejects_handshake, valid_token_registers_device_and_can_update_manifest (+3) |
| `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java` | handleTextMessage, handleRequest, handleNotification, handleToolManifest, handleResponse |
| `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java` | isOpen, send, close, manifest |
| `android/app/src/main/java/com/agentplatform/android/core/ws/WsClient.kt` | connect, openOnce, scheduleReconnect |
| `common/common-security/src/main/java/com/agentplatform/security/PrincipalContext.java` | require, requireUserId, requireDeviceId |
| `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceSession.java` | updateManifest, send |
| `common/common-security/src/main/java/com/agentplatform/security/JwtUtil.java` | issueDeviceToken, baseBuilder |
| `auth-service/src/main/java/com/agentplatform/auth/controller/UserPreferenceController.java` | get, put |
| `device-hub-service/src/main/java/com/agentplatform/hub/controller/InternalToolController.java` | call |
| `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceProvisioner.java` | provision |

## Entry Points

Start here when exploring this area:

- **`TokenAwareHandshakeHandler`** (Class) — `device-hub-service/src/main/java/com/agentplatform/hub/ws/TokenAwareHandshakeHandler.java:23`
- **`isOpen`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java:47`
- **`send`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java:53`
- **`close`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java:72`
- **`handleTextMessage`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java:80`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `TokenAwareHandshakeHandler` | Class | `device-hub-service/src/main/java/com/agentplatform/hub/ws/TokenAwareHandshakeHandler.java` | 23 |
| `isOpen` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java` | 47 |
| `send` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java` | 53 |
| `close` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java` | 72 |
| `handleTextMessage` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java` | 80 |
| `handleRequest` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java` | 97 |
| `handleNotification` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java` | 110 |
| `handleToolManifest` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java` | 121 |
| `handleResponse` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java` | 133 |
| `call` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/controller/InternalToolController.java` | 55 |
| `provision` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceProvisioner.java` | 25 |
| `updateManifest` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceSession.java` | 25 |
| `send` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceSession.java` | 31 |
| `getSession` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceRegistry.java` | 24 |
| `provision` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceProvisioner.java` | 17 |
| `issueDeviceToken` | Method | `common/common-security/src/main/java/com/agentplatform/security/JwtUtil.java` | 48 |
| `baseBuilder` | Method | `common/common-security/src/main/java/com/agentplatform/security/JwtUtil.java` | 70 |
| `get` | Method | `chat-service/src/main/java/com/agentplatform/chat/service/SessionService.java` | 39 |
| `get` | Method | `chat-service/src/main/java/com/agentplatform/chat/controller/SessionController.java` | 44 |
| `issueDeviceToken` | Method | `auth-service/src/main/java/com/agentplatform/auth/service/JwtService.java` | 39 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Run → GetContent` | cross_community | 7 |
| `CreateEnrollment → GetContent` | cross_community | 6 |
| `CreateEnrollment → GetUpdatedAt` | cross_community | 6 |
| `List → GetContent` | cross_community | 6 |
| `List → GetUpdatedAt` | cross_community | 6 |
| `Create → GetContent` | cross_community | 6 |
| `Create → GetUpdatedAt` | cross_community | 6 |
| `Get → GetContent` | cross_community | 6 |
| `Get → GetUpdatedAt` | cross_community | 6 |
| `List → GetContent` | cross_community | 6 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Registry | 5 calls |
| Exception | 4 calls |
| Call | 4 calls |
| Protocol | 3 calls |
| Filter | 2 calls |
| Ai | 2 calls |
| Security | 2 calls |
| Entity | 2 calls |

## How to Explore

1. `gitnexus_context({name: "TokenAwareHandshakeHandler"})` — see callers and callees
2. `gitnexus_query({query: "ws"})` — find related execution flows
3. Read key files listed above for implementation details
