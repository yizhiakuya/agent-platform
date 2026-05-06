---
name: call
description: "Skill for the Call area of agent-platform. 13 symbols across 3 files."
---

# Call

13 symbols | 3 files | Cohesion: 75%

## When to Use

- Working with code in `device-hub-service/`
- Understanding how register, complete, cancel work
- Modifying call-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `device-hub-service/src/test/java/com/agentplatform/hub/call/PendingCallRegistryTest.java` | complete_resolves_deferredResult_and_clears_map, timeout_invokes_cancelHook_and_resolves_with_TOOL_TIMEOUT, cancel_resolves_with_TOOL_CANCELLED_and_clears_map, complete_after_already_resolved_returns_false, complete_unknown_callId_returns_false (+2) |
| `device-hub-service/src/main/java/com/agentplatform/hub/call/PendingCallRegistry.java` | register, complete, cancel, pendingCount |
| `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | send, deliverFakeResult |

## Entry Points

Start here when exploring this area:

- **`register`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/call/PendingCallRegistry.java:52`
- **`complete`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/call/PendingCallRegistry.java:92`
- **`cancel`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/call/PendingCallRegistry.java:105`
- **`pendingCount`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/call/PendingCallRegistry.java:117`
- **`send`** (Method) — `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java:85`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `register` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/call/PendingCallRegistry.java` | 52 |
| `complete` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/call/PendingCallRegistry.java` | 92 |
| `cancel` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/call/PendingCallRegistry.java` | 105 |
| `pendingCount` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/call/PendingCallRegistry.java` | 117 |
| `send` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | 85 |
| `deliverFakeResult` | Method | `device-hub-service/src/main/java/com/agentplatform/hub/registry/MockDeviceSession.java` | 105 |
| `complete_resolves_deferredResult_and_clears_map` | Method | `device-hub-service/src/test/java/com/agentplatform/hub/call/PendingCallRegistryTest.java` | 42 |
| `timeout_invokes_cancelHook_and_resolves_with_TOOL_TIMEOUT` | Method | `device-hub-service/src/test/java/com/agentplatform/hub/call/PendingCallRegistryTest.java` | 57 |
| `cancel_resolves_with_TOOL_CANCELLED_and_clears_map` | Method | `device-hub-service/src/test/java/com/agentplatform/hub/call/PendingCallRegistryTest.java` | 73 |
| `complete_after_already_resolved_returns_false` | Method | `device-hub-service/src/test/java/com/agentplatform/hub/call/PendingCallRegistryTest.java` | 88 |
| `complete_unknown_callId_returns_false` | Method | `device-hub-service/src/test/java/com/agentplatform/hub/call/PendingCallRegistryTest.java` | 98 |
| `cancel_unknown_callId_returns_false` | Method | `device-hub-service/src/test/java/com/agentplatform/hub/call/PendingCallRegistryTest.java` | 104 |
| `cancel_after_complete_returns_false_no_double_resolution` | Method | `device-hub-service/src/test/java/com/agentplatform/hub/call/PendingCallRegistryTest.java` | 110 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Exception | 5 calls |
| Filter | 1 calls |
| Ai | 1 calls |
| Ws | 1 calls |

## How to Explore

1. `gitnexus_context({name: "register"})` — see callers and callees
2. `gitnexus_query({query: "call"})` — find related execution flows
3. Read key files listed above for implementation details
