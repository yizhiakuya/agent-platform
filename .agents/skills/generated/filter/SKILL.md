---
name: filter
description: "Skill for the Filter area of agent-platform. 23 symbols across 13 files."
---

# Filter

23 symbols | 13 files | Cohesion: 78%

## When to Use

- Working with code in `common/`
- Understanding how onDestroy, close, updateManifest work
- Modifying filter-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `common/common-security/src/main/java/com/agentplatform/security/PrincipalContext.java` | set, current, clear |
| `chat-service/src/main/java/com/agentplatform/chat/filter/ChatAuthFilter.java` | doFilterInternal, resolveTrustedHeaders, resolveBearer |
| `agent-service/src/main/java/com/agentplatform/agent/filter/AgentAuthFilter.java` | doFilterInternal, resolveTrustedHeaders, resolveBearer |
| `gateway/src/main/java/com/agentplatform/gateway/filter/JwtAuthFilter.java` | filter, isProtected, reject |
| `auth-service/src/main/java/com/agentplatform/auth/filter/PathBasedJwtFilter.java` | doFilterInternal, isProtected |
| `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceHandshakeInterceptor.java` | beforeHandshake, extractBearerToken |
| `android/app/src/main/java/com/agentplatform/android/service/AgentForegroundService.kt` | onDestroy |
| `android/app/src/main/java/com/agentplatform/android/core/ws/WsClient.kt` | close |
| `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java` | updateManifest |
| `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | updateManifest |

## Entry Points

Start here when exploring this area:

- **`onDestroy`** (Method) — `android/app/src/main/java/com/agentplatform/android/service/AgentForegroundService.kt:184`
- **`close`** (Method) — `android/app/src/main/java/com/agentplatform/android/core/ws/WsClient.kt:49`
- **`updateManifest`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java:49`
- **`updateManifest`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java:83`
- **`doFilterInternal`** (Method) — `common/common-security/src/main/java/com/agentplatform/security/TrustedHeaderAuthFilter.java:26`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `onDestroy` | Method | `android/app/src/main/java/com/agentplatform/android/service/AgentForegroundService.kt` | 184 |
| `close` | Method | `android/app/src/main/java/com/agentplatform/android/core/ws/WsClient.kt` | 49 |
| `updateManifest` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java` | 49 |
| `updateManifest` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | 83 |
| `doFilterInternal` | Method | `common/common-security/src/main/java/com/agentplatform/security/TrustedHeaderAuthFilter.java` | 26 |
| `set` | Method | `common/common-security/src/main/java/com/agentplatform/security/PrincipalContext.java` | 14 |
| `current` | Method | `common/common-security/src/main/java/com/agentplatform/security/PrincipalContext.java` | 15 |
| `clear` | Method | `common/common-security/src/main/java/com/agentplatform/security/PrincipalContext.java` | 16 |
| `doFilterInternal` | Method | `common/common-security/src/main/java/com/agentplatform/security/JwtAuthenticationFilter.java` | 35 |
| `doFilterInternal` | Method | `chat-service/src/main/java/com/agentplatform/chat/filter/ChatAuthFilter.java` | 39 |
| `resolveTrustedHeaders` | Method | `chat-service/src/main/java/com/agentplatform/chat/filter/ChatAuthFilter.java` | 63 |
| `resolveBearer` | Method | `chat-service/src/main/java/com/agentplatform/chat/filter/ChatAuthFilter.java` | 73 |
| `doFilterInternal` | Method | `auth-service/src/main/java/com/agentplatform/auth/filter/PathBasedJwtFilter.java` | 40 |
| `isProtected` | Method | `auth-service/src/main/java/com/agentplatform/auth/filter/PathBasedJwtFilter.java` | 73 |
| `doFilterInternal` | Method | `agent-service/src/main/java/com/agentplatform/agent/filter/AgentAuthFilter.java` | 44 |
| `resolveTrustedHeaders` | Method | `agent-service/src/main/java/com/agentplatform/agent/filter/AgentAuthFilter.java` | 68 |
| `resolveBearer` | Method | `agent-service/src/main/java/com/agentplatform/agent/filter/AgentAuthFilter.java` | 78 |
| `filter` | Method | `gateway/src/main/java/com/agentplatform/gateway/filter/JwtAuthFilter.java` | 48 |
| `isProtected` | Method | `gateway/src/main/java/com/agentplatform/gateway/filter/JwtAuthFilter.java` | 91 |
| `reject` | Method | `gateway/src/main/java/com/agentplatform/gateway/filter/JwtAuthFilter.java` | 95 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `DoFilterInternal → GetId` | cross_community | 6 |
| `DoFilterInternal → GetUserId` | cross_community | 6 |
| `DoFilterInternal → GetTitle` | cross_community | 6 |
| `DoFilterInternal → GetCreatedAt` | cross_community | 6 |
| `DoFilterInternal → GetId` | cross_community | 6 |
| `DoFilterInternal → GetUserId` | cross_community | 6 |
| `DoFilterInternal → GetTitle` | cross_community | 6 |
| `DoFilterInternal → GetCreatedAt` | cross_community | 6 |
| `DoFilterInternal → GetId` | cross_community | 5 |
| `DoFilterInternal → GetUserId` | cross_community | 5 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Security | 6 calls |
| Api | 1 calls |
| Exception | 1 calls |
| Ws | 1 calls |

## How to Explore

1. `gitnexus_context({name: "onDestroy"})` — see callers and callees
2. `gitnexus_query({query: "filter"})` — find related execution flows
3. Read key files listed above for implementation details
