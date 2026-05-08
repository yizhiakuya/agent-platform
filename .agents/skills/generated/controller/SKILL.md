---
name: controller
description: "Skill for the Controller area of agent-platform. 4 symbols across 4 files."
---

# Controller

4 symbols | 4 files | Cohesion: 60%

## When to Use

- Working with code in `chat-service/`
- Understanding how listBySession, findBySessionIdOrderByCreatedAtAsc, messages work
- Modifying controller-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `chat-service/src/main/java/com/agentplatform/chat/service/MessageService.java` | listBySession |
| `chat-service/src/main/java/com/agentplatform/chat/repository/MessageRepository.java` | findBySessionIdOrderByCreatedAtAsc |
| `chat-service/src/main/java/com/agentplatform/chat/controller/SessionController.java` | messages |
| `chat-service/src/main/java/com/agentplatform/chat/controller/InternalChatController.java` | listMessages |

## Entry Points

Start here when exploring this area:

- **`listBySession`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/service/MessageService.java:66`
- **`findBySessionIdOrderByCreatedAtAsc`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/repository/MessageRepository.java:9`
- **`messages`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/controller/SessionController.java:49`
- **`listMessages`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/controller/InternalChatController.java:55`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `listBySession` | Method | `chat-service/src/main/java/com/agentplatform/chat/service/MessageService.java` | 66 |
| `findBySessionIdOrderByCreatedAtAsc` | Method | `chat-service/src/main/java/com/agentplatform/chat/repository/MessageRepository.java` | 9 |
| `messages` | Method | `chat-service/src/main/java/com/agentplatform/chat/controller/SessionController.java` | 49 |
| `listMessages` | Method | `chat-service/src/main/java/com/agentplatform/chat/controller/InternalChatController.java` | 55 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Run → GetContent` | cross_community | 7 |
| `Messages → GetUpdatedAt` | cross_community | 6 |
| `Run → GetUserId` | cross_community | 5 |
| `Run → GetMetadata` | cross_community | 5 |
| `Run → GetId` | cross_community | 5 |
| `Run → GetSessionId` | cross_community | 5 |
| `Run → GetRole` | cross_community | 5 |
| `ListMessages → GetUserId` | cross_community | 4 |
| `ListMessages → GetMetadata` | cross_community | 4 |
| `ListMessages → GetId` | cross_community | 4 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Service | 1 calls |
| Entity | 1 calls |
| Ws | 1 calls |

## How to Explore

1. `gitnexus_context({name: "listBySession"})` — see callers and callees
2. `gitnexus_query({query: "controller"})` — find related execution flows
3. Read key files listed above for implementation details
