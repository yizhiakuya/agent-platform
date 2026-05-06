---
name: exception
description: "Skill for the Exception area of agent-platform. 20 symbols across 11 files."
---

# Exception

20 symbols | 11 files | Cohesion: 44%

## When to Use

- Working with code in `auth-service/`
- Understanding how UserPreference, toManifestParams, defaultManifest work
- Modifying exception-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `auth-service/src/main/java/com/agentplatform/auth/entity/UserPreference.java` | UserPreference, setUserId, setContent, setUpdatedAt |
| `device-hub-service/src/test/java/com/agentplatform/hub/InternalToolControllerTest.java` | mocked_tool_call_returns_fake_result, short_timeout_resolves_with_TOOL_TIMEOUT_error, missing_deviceId_returns_400 |
| `auth-service/src/main/java/com/agentplatform/auth/exception/GlobalExceptionHandler.java` | handleValidation, handleIllegalState, handleResponseStatus |
| `chat-service/src/main/java/com/agentplatform/chat/exception/GlobalExceptionHandler.java` | handleIllegalState, handleResponseStatus |
| `agent-service/src/main/java/com/agentplatform/agent/exception/GlobalExceptionHandler.java` | handleIllegalState, handleResponseStatus |
| `android/app/src/main/java/com/agentplatform/android/service/AgentForegroundService.kt` | onWsConnected |
| `android/app/src/main/java/com/agentplatform/android/core/tool/ToolRegistry.kt` | toManifestParams |
| `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | defaultManifest |
| `auth-service/src/main/java/com/agentplatform/auth/service/UserPreferenceService.java` | put |
| `device-hub-service/src/main/java/com/agentplatform/hub/exception/GlobalExceptionHandler.java` | handleResponseStatus |

## Entry Points

Start here when exploring this area:

- **`UserPreference`** (Class) — `auth-service/src/main/java/com/agentplatform/auth/entity/UserPreference.java:16`
- **`toManifestParams`** (Method) — `android/app/src/main/java/com/agentplatform/android/core/tool/ToolRegistry.kt:27`
- **`defaultManifest`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java:58`
- **`handleIllegalState`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/exception/GlobalExceptionHandler.java:22`
- **`put`** (Method) — `auth-service/src/main/java/com/agentplatform/auth/service/UserPreferenceService.java:34`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `UserPreference` | Class | `auth-service/src/main/java/com/agentplatform/auth/entity/UserPreference.java` | 16 |
| `toManifestParams` | Method | `android/app/src/main/java/com/agentplatform/android/core/tool/ToolRegistry.kt` | 27 |
| `defaultManifest` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | 58 |
| `handleIllegalState` | Method | `chat-service/src/main/java/com/agentplatform/chat/exception/GlobalExceptionHandler.java` | 22 |
| `put` | Method | `auth-service/src/main/java/com/agentplatform/auth/service/UserPreferenceService.java` | 34 |
| `handleValidation` | Method | `auth-service/src/main/java/com/agentplatform/auth/exception/GlobalExceptionHandler.java` | 24 |
| `handleIllegalState` | Method | `auth-service/src/main/java/com/agentplatform/auth/exception/GlobalExceptionHandler.java` | 37 |
| `setUserId` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/UserPreference.java` | 31 |
| `setContent` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/UserPreference.java` | 34 |
| `setUpdatedAt` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/UserPreference.java` | 37 |
| `handleIllegalState` | Method | `agent-service/src/main/java/com/agentplatform/agent/exception/GlobalExceptionHandler.java` | 22 |
| `handleResponseStatus` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/exception/GlobalExceptionHandler.java` | 13 |
| `handleResponseStatus` | Method | `chat-service/src/main/java/com/agentplatform/chat/exception/GlobalExceptionHandler.java` | 14 |
| `handleResponseStatus` | Method | `auth-service/src/main/java/com/agentplatform/auth/exception/GlobalExceptionHandler.java` | 16 |
| `handleResponseStatus` | Method | `agent-service/src/main/java/com/agentplatform/agent/exception/GlobalExceptionHandler.java` | 14 |
| `getReason` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/ToolBlockedException.java` | 16 |
| `onWsConnected` | Method | `android/app/src/main/java/com/agentplatform/android/service/AgentForegroundService.kt` | 89 |
| `mocked_tool_call_returns_fake_result` | Method | `device-hub-service/src/test/java/com/agentplatform/hub/InternalToolControllerTest.java` | 41 |
| `short_timeout_resolves_with_TOOL_TIMEOUT_error` | Method | `device-hub-service/src/test/java/com/agentplatform/hub/InternalToolControllerTest.java` | 70 |
| `missing_deviceId_returns_400` | Method | `device-hub-service/src/test/java/com/agentplatform/hub/InternalToolControllerTest.java` | 98 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Stream → UserPreference` | cross_community | 6 |
| `Stream → SetUserId` | cross_community | 6 |
| `Stream → SetContent` | cross_community | 6 |
| `Stream → SetUpdatedAt` | cross_community | 6 |
| `HandleWithLlm → UserPreference` | cross_community | 5 |
| `ExtractAsync → UserPreference` | cross_community | 5 |
| `ExtractAsync → SetUserId` | cross_community | 5 |
| `ExtractAsync → SetContent` | cross_community | 5 |
| `ExtractAsync → SetUpdatedAt` | cross_community | 5 |
| `HandleWithLlm → SetUserId` | cross_community | 4 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Photos | 2 calls |
| Entity | 2 calls |

## How to Explore

1. `gitnexus_context({name: "UserPreference"})` — see callers and callees
2. `gitnexus_query({query: "exception"})` — find related execution flows
3. Read key files listed above for implementation details
