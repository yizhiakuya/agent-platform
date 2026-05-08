---
name: chat
description: "Skill for the Chat area of agent-platform. 42 symbols across 13 files."
---

# Chat

42 symbols | 13 files | Cohesion: 64%

## When to Use

- Working with code in `agent-service/`
- Understanding how isCurated, composeUserText, buildToolUnionList work
- Modifying chat-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `agent-service/src/main/java/com/agentplatform/agent/chat/ChatService.java` | handleWithLlm, loadUserPrefs, handle, handleInternal, ensureSession (+6) |
| `agent-service/src/main/java/com/agentplatform/agent/chat/PromptAssembler.java` | composeUserText, buildToolUnionList, formatMemoryBlock, appendFact, buildSystemText (+3) |
| `agent-service/src/main/java/com/agentplatform/agent/chat/SseEvent.java` | session, userMessage, toolCallStarted, toolCallResult, error (+1) |
| `agent-service/src/main/java/com/agentplatform/agent/chat/AgentLoopRunner.java` | logCacheUsage, run, executeOneToolUse, safeSend |
| `agent-service/src/main/java/com/agentplatform/agent/client/InternalChatFeignClient.java` | listMessages, createSession, writeMessage |
| `agent-service/src/main/java/com/agentplatform/agent/ai/SkillLoadCallback.java` | name, toAnthropicTool, availableNames |
| `chat-service/src/main/java/com/agentplatform/chat/entity/MemoryFact.java` | isCurated |
| `agent-service/src/main/java/com/agentplatform/agent/chat/HistoryReplayer.java` | loadAsParams |
| `agent-service/src/main/java/com/agentplatform/agent/client/AuthInternalClient.java` | getPreferences |
| `agent-service/src/main/java/com/agentplatform/agent/ai/SkillRegistry.java` | all |

## Entry Points

Start here when exploring this area:

- **`isCurated`** (Method) — `chat-service/src/main/java/com/agentplatform/chat/entity/MemoryFact.java:74`
- **`composeUserText`** (Method) — `agent-service/src/main/java/com/agentplatform/agent/chat/PromptAssembler.java:63`
- **`buildToolUnionList`** (Method) — `agent-service/src/main/java/com/agentplatform/agent/chat/PromptAssembler.java:89`
- **`formatMemoryBlock`** (Method) — `agent-service/src/main/java/com/agentplatform/agent/chat/PromptAssembler.java:112`
- **`appendFact`** (Method) — `agent-service/src/main/java/com/agentplatform/agent/chat/PromptAssembler.java:130`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `isCurated` | Method | `chat-service/src/main/java/com/agentplatform/chat/entity/MemoryFact.java` | 74 |
| `composeUserText` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/PromptAssembler.java` | 63 |
| `buildToolUnionList` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/PromptAssembler.java` | 89 |
| `formatMemoryBlock` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/PromptAssembler.java` | 112 |
| `appendFact` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/PromptAssembler.java` | 130 |
| `loadAsParams` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/HistoryReplayer.java` | 38 |
| `handleWithLlm` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/ChatService.java` | 151 |
| `loadUserPrefs` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/ChatService.java` | 289 |
| `logCacheUsage` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/AgentLoopRunner.java` | 230 |
| `listMessages` | Method | `agent-service/src/main/java/com/agentplatform/agent/client/InternalChatFeignClient.java` | 35 |
| `getPreferences` | Method | `agent-service/src/main/java/com/agentplatform/agent/client/AuthInternalClient.java` | 18 |
| `all` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/SkillRegistry.java` | 168 |
| `name` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/SkillLoadCallback.java` | 55 |
| `toAnthropicTool` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/SkillLoadCallback.java` | 63 |
| `availableNames` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/SkillLoadCallback.java` | 132 |
| `getBundle` | Method | `agent-service/src/main/java/com/agentplatform/agent/ai/PersonaLoader.java` | 80 |
| `session` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/SseEvent.java` | 29 |
| `userMessage` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/SseEvent.java` | 34 |
| `handle` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/ChatService.java` | 123 |
| `handleInternal` | Method | `agent-service/src/main/java/com/agentplatform/agent/chat/ChatService.java` | 127 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Run → GetContent` | cross_community | 7 |
| `Stream → GetContent` | cross_community | 6 |
| `Stream → GetUpdatedAt` | cross_community | 6 |
| `Stream → UserPreference` | cross_community | 6 |
| `Stream → SetUserId` | cross_community | 6 |
| `Stream → SetContent` | cross_community | 6 |
| `Stream → SetUpdatedAt` | cross_community | 6 |
| `Stream → CreateSession` | intra_community | 5 |
| `HandleWithLlm → SanitizeForLlm` | cross_community | 5 |
| `HandleWithLlm → UserPreference` | cross_community | 5 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Ai | 12 calls |
| Exception | 8 calls |
| Ws | 2 calls |
| Filter | 1 calls |
| Controller | 1 calls |

## How to Explore

1. `gitnexus_context({name: "isCurated"})` — see callers and callees
2. `gitnexus_query({query: "chat"})` — find related execution flows
3. Read key files listed above for implementation details
