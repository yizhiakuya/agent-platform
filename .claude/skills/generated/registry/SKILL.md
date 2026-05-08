---
name: registry
description: "Skill for the Registry area of agent-platform. 24 symbols across 8 files."
---

# Registry

24 symbols | 8 files | Cohesion: 77%

## When to Use

- Working with code in `device-hub-service/`
- Understanding how WsDeviceSession, MockDeviceSession, MockDeviceProvisioner work
- Modifying registry-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceSession.java` | close, deviceId, userId, connectedAt, isOpen (+2) |
| `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | deviceId, userId, close, isOpen, MockDeviceSession |
| `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceRegistry.java` | online, offline, listOnlineByUser, listAll |
| `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java` | deviceId, userId, WsDeviceSession |
| `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java` | afterConnectionEstablished, afterConnectionClosed |
| `device-hub-service/src/main/java/com/agentplatform/hub/controller/InternalDeviceController.java` | online |
| `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceProvisioner.java` | MockDeviceProvisioner |
| `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceProvisioner.java` | DeviceProvisioner |

## Entry Points

Start here when exploring this area:

- **`WsDeviceSession`** (Class) — `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java:25`
- **`MockDeviceSession`** (Class) — `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java:29`
- **`MockDeviceProvisioner`** (Class) — `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceProvisioner.java:8`
- **`deviceId`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java:44`
- **`userId`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java:45`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `WsDeviceSession` | Class | `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java` | 25 |
| `MockDeviceSession` | Class | `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | 29 |
| `MockDeviceProvisioner` | Class | `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceProvisioner.java` | 8 |
| `DeviceSession` | Interface | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceSession.java` | 13 |
| `DeviceProvisioner` | Interface | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceProvisioner.java` | 11 |
| `deviceId` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java` | 44 |
| `userId` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/WsDeviceSession.java` | 45 |
| `afterConnectionEstablished` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java` | 65 |
| `afterConnectionClosed` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/ws/DeviceWsHandler.java` | 150 |
| `deviceId` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | 78 |
| `userId` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | 79 |
| `close` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | 120 |
| `close` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceSession.java` | 33 |
| `online` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceRegistry.java` | 28 |
| `offline` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceRegistry.java` | 36 |
| `online` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/controller/InternalDeviceController.java` | 31 |
| `isOpen` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | 81 |
| `deviceId` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceSession.java` | 15 |
| `userId` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceSession.java` | 17 |
| `connectedAt` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceSession.java` | 19 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `AfterConnectionEstablished → UserPreference` | cross_community | 4 |
| `AfterConnectionEstablished → SetUserId` | cross_community | 4 |
| `AfterConnectionEstablished → SetContent` | cross_community | 4 |
| `AfterConnectionEstablished → SetUpdatedAt` | cross_community | 4 |
| `AfterConnectionClosed → IsOpen` | cross_community | 4 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Ws | 4 calls |
| Exception | 2 calls |

## How to Explore

1. `gitnexus_context({name: "WsDeviceSession"})` — see callers and callees
2. `gitnexus_query({query: "registry"})` — find related execution flows
3. Read key files listed above for implementation details
