---
name: service
description: "Skill for the Service area of agent-platform. 28 symbols across 14 files."
---

# Service

28 symbols | 14 files | Cohesion: 80%

## When to Use

- Working with code in `chat-service/`
- Understanding how Message, touchLastMessage, sanitizeContent work
- Modifying service-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `chat-service/src/main/java/com/agentplatform/chat/entity/Message.java` | Message, setSessionId, setRole, setContent, setMetadata |
| `chat-service/src/main/java/com/agentplatform/chat/service/PiiSanitizer.java` | sanitizeContent, sanitizeMetadata, redact, looksBase64 |
| `chat-service/src/main/java/com/agentplatform/chat/service/SessionService.java` | touchLastMessage, delete, requireOwned |
| `chat-service/src/main/java/com/agentplatform/chat/service/MemoryService.java` | saveFact, queryFacts, toVectorLiteral |
| `chat-service/src/main/java/com/agentplatform/chat/entity/Session.java` | setLastMessageAt, getUserId |
| `auth-service/src/main/java/com/agentplatform/auth/entity/User.java` | getId, getPasswordHash |
| `chat-service/src/main/java/com/agentplatform/chat/controller/InternalMemoryController.java` | saveFact, queryFacts |
| `chat-service/src/main/java/com/agentplatform/chat/service/MessageService.java` | write |
| `chat-service/src/main/java/com/agentplatform/chat/controller/InternalChatController.java` | writeMessage |
| `auth-service/src/main/java/com/agentplatform/auth/service/JwtService.java` | issueUserToken |

## Entry Points

Start here when exploring this area:

- **`Message`** (Class) — `chat-service/src/main/java/com/agentplatform/chat/entity/Message.java:15`
- **`touchLastMessage`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/service/SessionService.java:49`
- **`sanitizeContent`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/service/PiiSanitizer.java:46`
- **`sanitizeMetadata`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/service/PiiSanitizer.java:57`
- **`redact`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/service/PiiSanitizer.java:62`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `Message` | Class | `chat-service/src/main/java/com/agentplatform/chat/entity/Message.java` | 15 |
| `touchLastMessage` | Method | `chat-service/src/main/java/com/agentplatform/chat/service/SessionService.java` | 49 |
| `sanitizeContent` | Method | `chat-service/src/main/java/com/agentplatform/chat/service/PiiSanitizer.java` | 46 |
| `sanitizeMetadata` | Method | `chat-service/src/main/java/com/agentplatform/chat/service/PiiSanitizer.java` | 57 |
| `redact` | Method | `chat-service/src/main/java/com/agentplatform/chat/service/PiiSanitizer.java` | 62 |
| `looksBase64` | Method | `chat-service/src/main/java/com/agentplatform/chat/service/PiiSanitizer.java` | 87 |
| `write` | Method | `chat-service/src/main/java/com/agentplatform/chat/service/MessageService.java` | 43 |
| `setLastMessageAt` | Method | `chat-service/src/main/java/com/agentplatform/chat/entity/Session.java` | 41 |
| `setSessionId` | Method | `chat-service/src/main/java/com/agentplatform/chat/entity/Message.java` | 42 |
| `setRole` | Method | `chat-service/src/main/java/com/agentplatform/chat/entity/Message.java` | 44 |
| `setContent` | Method | `chat-service/src/main/java/com/agentplatform/chat/entity/Message.java` | 46 |
| `setMetadata` | Method | `chat-service/src/main/java/com/agentplatform/chat/entity/Message.java` | 48 |
| `writeMessage` | Method | `chat-service/src/main/java/com/agentplatform/chat/controller/InternalChatController.java` | 45 |
| `issueUserToken` | Method | `auth-service/src/main/java/com/agentplatform/auth/service/JwtService.java` | 35 |
| `login` | Method | `auth-service/src/main/java/com/agentplatform/auth/service/AuthService.java` | 39 |
| `getId` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/User.java` | 29 |
| `getPasswordHash` | Method | `auth-service/src/main/java/com/agentplatform/auth/entity/User.java` | 35 |
| `findByUsername` | Method | `auth-service/src/main/java/com/agentplatform/auth/repository/UserRepository.java` | 9 |
| `login` | Method | `auth-service/src/main/java/com/agentplatform/auth/controller/AuthController.java` | 41 |
| `saveFact` | Method | `chat-service/src/main/java/com/agentplatform/chat/service/MemoryService.java` | 52 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Delete → GetContent` | cross_community | 6 |
| `Delete → GetUpdatedAt` | cross_community | 6 |
| `Verify → GetUserId` | cross_community | 6 |
| `DoFilterInternal → GetUserId` | cross_community | 6 |
| `DoFilterInternal → GetUserId` | cross_community | 6 |
| `Login → BaseBuilder` | cross_community | 5 |
| `DoFilterInternal → GetUserId` | cross_community | 5 |
| `Run → GetUserId` | cross_community | 5 |
| `DoFilterInternal → GetUserId` | cross_community | 5 |
| `BeforeHandshake → GetUserId` | cross_community | 5 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Exception | 2 calls |
| Entity | 2 calls |
| Ai | 1 calls |
| Ws | 1 calls |
| Security | 1 calls |

## How to Explore

1. `gitnexus_context({name: "Message"})` — see callers and callees
2. `gitnexus_query({query: "service"})` — find related execution flows
3. Read key files listed above for implementation details
