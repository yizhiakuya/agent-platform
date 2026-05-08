---
name: ai
description: "Skill for the Ai area of agent-platform. 49 symbols across 24 files."
---

# Ai

49 symbols | 24 files | Cohesion: 65%

## When to Use

- Working with code in `agent-service/`
- Understanding how LoggingPreInterceptor, size, promoteHotFacts work
- Modifying ai-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `agent-service/src/main/java/com/agentplatform/agent/ai/RemoteToolCallback.java` | stripB64ForLlmAndCollect, sniffMime, executeToolUse, stripB64ForLlm, wireError (+5) |
| `agent-service/src/test/java/com/agentplatform/agent/ai/RemoteToolCallbackBinaryStripTest.java` | replacesPlainB64FieldWithPlaceholderAndCollects, visionPreemptsThumbWhenBothPresent, thumbAloneStillCollected, recursesArraysAndNestedObjects, emptyB64NotCollectedButStillPlaceholdered (+1) |
| `agent-service/src/main/java/com/agentplatform/agent/ai/MemoryExtractor.java` | extractAsync, runExtractionBatch, sliceFirstArray, safe |
| `agent-service/src/main/java/com/agentplatform/agent/ai/SkillRegistry.java` | get, load, parse, safeUri |
| `agent-service/src/main/java/com/agentplatform/agent/client/InternalChatFeignClient.java` | saveFact, queryFacts |
| `agent-service/src/main/java/com/agentplatform/agent/ai/ToolPreInterceptor.java` | before, ToolPreInterceptor |
| `agent-service/src/main/java/com/agentplatform/agent/ai/LoggingPreInterceptor.java` | before, LoggingPreInterceptor |
| `agent-service/src/main/java/com/agentplatform/agent/ai/SkillLoadCallback.java` | executeToolUse, parseName |
| `agent-service/src/main/java/com/agentplatform/agent/ai/PersonaLoader.java` | load, loadOrDefault |
| `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceRegistry.java` | size |

## Entry Points

Start here when exploring this area:

- **`LoggingPreInterceptor`** (Class) — `agent-service/src/main/java/com/agentplatform/agent/ai/LoggingPreInterceptor.java:17`
- **`size`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceRegistry.java:54`
- **`promoteHotFacts`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/service/MemoryService.java:174`
- **`promote`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/controller/InternalMemoryController.java:64`
- **`chatClients`** (Method) — `agent-service/src/main/java/com/agentplatform/agent/config/AgentBeans.java:36`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `LoggingPreInterceptor` | Class | `agent-service/src/main/java/com/agentplatform/agent/ai/LoggingPreInterceptor.java` | 17 |
| `ToolPreInterceptor` | Interface | `agent-service/src/main/java/com/agentplatform/agent/ai/ToolPreInterceptor.java` | 21 |
| `size` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/DeviceRegistry.java` | 54 |
| `promoteHotFacts` | Method | `chat-service/src/main/java/com/agentplatform/chat/service/MemoryService.java` | 174 |
| `promote` | Method | `chat-service/src/main/java/com/agentplatform/chat/controller/InternalMemoryController.java` | 64 |
| `chatClients` | Method | `agent-service/src/main/java/com/agentplatform/agent/config/AgentBeans.java` | 36 |
| `stripB64ForLlmAndCollect` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/RemoteToolCallback.java` | 270 |
| `sniffMime` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/RemoteToolCallback.java` | 331 |
| `recallMemories` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/ChatService.java` | 301 |
| `saveFact` | Method | `agent-service/src/main/java/com/agentplatform/agent/client/InternalChatFeignClient.java` | 42 |
| `queryFacts` | Method | `agent-service/src/main/java/com/agentplatform/agent/client/InternalChatFeignClient.java` | 48 |
| `extractAsync` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/MemoryExtractor.java` | 77 |
| `runExtractionBatch` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/MemoryExtractor.java` | 100 |
| `sliceFirstArray` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/MemoryExtractor.java` | 188 |
| `safe` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/MemoryExtractor.java` | 195 |
| `embed` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/EmbeddingService.java` | 111 |
| `dispatch` | Method | `agent-service/src/main/java/com/agentplatform/agent/client/DeviceToolDispatcher.java` | 41 |
| `before` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/ToolPreInterceptor.java` | 22 |
| `executeToolUse` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/RemoteToolCallback.java` | 122 |
| `stripB64ForLlm` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/RemoteToolCallback.java` | 340 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `ExtractAsync → Require` | cross_community | 6 |
| `ExtractAsync → UserIdAsUuid` | cross_community | 6 |
| `ExtractAsync → GetContent` | cross_community | 6 |
| `ExtractAsync → GetUpdatedAt` | cross_community | 6 |
| `HandleWithLlm → SanitizeForLlm` | cross_community | 5 |
| `HandleWithMock → Require` | cross_community | 5 |
| `HandleWithMock → UserIdAsUuid` | cross_community | 5 |
| `HandleWithMock → GetContent` | cross_community | 5 |
| `HandleWithMock → GetUpdatedAt` | cross_community | 5 |
| `ExecuteOneToolUse → SanitizeForLlm` | cross_community | 5 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Exception | 7 calls |
| Chat | 4 calls |
| Ws | 3 calls |

## How to Explore

1. `gitnexus_context({name: "LoggingPreInterceptor"})` — see callers and callees
2. `gitnexus_query({query: "ai"})` — find related execution flows
3. Read key files listed above for implementation details
