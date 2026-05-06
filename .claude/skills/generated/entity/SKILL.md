---
name: entity
description: "Skill for the Entity area of agent-platform. 61 symbols across 21 files."
---

# Entity

61 symbols | 21 files | Cohesion: 82%

## When to Use

- Working with code in `auth-service/`
- Understanding how Device, Enrollment, User work
- Modifying entity-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `auth-service/src/main/java/com/agentplatform/auth/entity/Device.java` | Device, setUserId, setName, setModel, setOsVersion (+6) |
| `auth-service/src/main/java/com/agentplatform/auth/entity/Enrollment.java` | getUserId, getUsedAt, setUsedAt, Enrollment, setTokenHash (+3) |
| `chat-service/src/main/java/com/agentplatform/chat/entity/Session.java` | getId, getTitle, getCreatedAt, getLastMessageAt, Session (+2) |
| `chat-service/src/main/java/com/agentplatform/chat/entity/Message.java` | getId, getSessionId, getRole, getContent, getMetadata (+1) |
| `auth-service/src/main/java/com/agentplatform/auth/entity/User.java` | User, getUsername, setUsername, setPasswordHash |
| `auth-service/src/main/java/com/agentplatform/auth/service/EnrollmentService.java` | redeem, create, sha256Hex |
| `chat-service/src/main/java/com/agentplatform/chat/service/SessionService.java` | listByUser, toDto, create |
| `auth-service/src/main/java/com/agentplatform/auth/controller/AuthController.java` | redeem, register |
| `auth-service/src/main/java/com/agentplatform/auth/service/DeviceService.java` | list, toDto |
| `auth-service/src/main/java/com/agentplatform/auth/controller/MeDeviceController.java` | list, createEnrollment |

## Entry Points

Start here when exploring this area:

- **`Device`** (Class) — `auth-service/src/main/java/com/agentplatform/auth/entity/Device.java:12`
- **`Enrollment`** (Class) — `auth-service/src/main/java/com/agentplatform/auth/entity/Enrollment.java:14`
- **`User`** (Class) — `auth-service/src/main/java/com/agentplatform/auth/entity/User.java:12`
- **`Session`** (Class) — `chat-service/src/main/java/com/agentplatform/chat/entity/Session.java:12`
- **`redeem`** (Method) — `auth-service/src/main/java/com/agentplatform/auth/service/EnrollmentService.java:72`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `Device` | Class | `auth-service/src/main/java/com/agentplatform/auth/entity/Device.java` | 12 |
| `Enrollment` | Class | `auth-service/src/main/java/com/agentplatform/auth/entity/Enrollment.java` | 14 |
| `User` | Class | `auth-service/src/main/java/com/agentplatform/auth/entity/User.java` | 12 |
| `Session` | Class | `chat-service/src/main/java/com/agentplatform/chat/entity/Session.java` | 12 |
| `redeem` | Method | `auth-service/src/main/java/com/agentplatform/auth/service/EnrollmentService.java` | 72 |
| `getUserId` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/Enrollment.java` | 36 |
| `getUsedAt` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/Enrollment.java` | 40 |
| `setUsedAt` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/Enrollment.java` | 41 |
| `setUserId` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/Device.java` | 44 |
| `setName` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/Device.java` | 46 |
| `setModel` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/Device.java` | 48 |
| `setOsVersion` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/Device.java` | 50 |
| `findByTokenHashForUpdate` | Method | `auth-service/src/main/java/com/agentplatform/auth/repository/EnrollmentRepository.java` | 22 |
| `redeem` | Method | `auth-service/src/main/java/com/agentplatform/auth/controller/AuthController.java` | 50 |
| `list` | Method | `auth-service/src/main/java/com/agentplatform/auth/service/DeviceService.java` | 20 |
| `toDto` | Method | `auth-service/src/main/java/com/agentplatform/auth/service/DeviceService.java` | 27 |
| `getId` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/Device.java` | 41 |
| `getName` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/Device.java` | 45 |
| `getModel` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/Device.java` | 47 |
| `getOsVersion` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/Device.java` | 49 |

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
| Ws | 5 calls |
| Service | 2 calls |
| Protocol | 1 calls |

## How to Explore

1. `gitnexus_context({name: "Device"})` — see callers and callees
2. `gitnexus_query({query: "entity"})` — find related execution flows
3. Read key files listed above for implementation details
