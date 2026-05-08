---
name: photos
description: "Skill for the Photos area of agent-platform. 37 symbols across 13 files."
---

# Photos

37 symbols | 13 files | Cohesion: 83%

## When to Use

- Working with code in `android/`
- Understanding how decodeJsonRpc, WsClient, VideosListRecentTool work
- Modifying photos-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/java/com/agentplatform/android/service/AgentForegroundService.kt` | onCreate, onWsMessage, handleNotification, buildNotification, updateNotification (+2) |
| `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosGetMetadataTool.kt` | PhotosGetMetadataTool, execute, s, l, i (+1) |
| `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosListAlbumsTool.kt` | PhotosListAlbumsTool, execute, Album |
| `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosGetFullTool.kt` | PhotosGetFullTool, execute, applyExifOrientation |
| `android/app/src/main/java/com/agentplatform/android/core/ws/WsClient.kt` | WsClient, send |
| `android/app/src/main/java/com/agentplatform/android/core/ws/JsonRpc.kt` | decodeJsonRpc, JsonRpcResponse |
| `android/app/src/main/java/com/agentplatform/android/core/tool/ToolRegistry.kt` | register, get |
| `android/app/src/main/java/com/agentplatform/android/core/tool/Tool.kt` | Tool, execute |
| `android/app/src/main/java/com/agentplatform/android/tools/videos/VideosListRecentTool.kt` | VideosListRecentTool, execute |
| `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosRecentScreenshotsTool.kt` | PhotosRecentScreenshotsTool, execute |

## Entry Points

Start here when exploring this area:

- **`decodeJsonRpc`** (Function) — `android/app/src/main/java/com/agentplatform/android/core/ws/JsonRpc.kt:55`
- **`WsClient`** (Class) — `android/app/src/main/java/com/agentplatform/android/core/ws/WsClient.kt:23`
- **`VideosListRecentTool`** (Class) — `android/app/src/main/java/com/agentplatform/android/tools/videos/VideosListRecentTool.kt:28`
- **`PhotosRecentScreenshotsTool`** (Class) — `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosRecentScreenshotsTool.kt:25`
- **`PhotosListRecentTool`** (Class) — `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosListRecentTool.kt:35`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `WsClient` | Class | `android/app/src/main/java/com/agentplatform/android/core/ws/WsClient.kt` | 23 |
| `VideosListRecentTool` | Class | `android/app/src/main/java/com/agentplatform/android/tools/videos/VideosListRecentTool.kt` | 28 |
| `PhotosRecentScreenshotsTool` | Class | `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosRecentScreenshotsTool.kt` | 25 |
| `PhotosListRecentTool` | Class | `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosListRecentTool.kt` | 35 |
| `PhotosListByAlbumTool` | Class | `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosListByAlbumTool.kt` | 25 |
| `PhotosListAlbumsTool` | Class | `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosListAlbumsTool.kt` | 24 |
| `PhotosGetMetadataTool` | Class | `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosGetMetadataTool.kt` | 21 |
| `PhotosGetFullTool` | Class | `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosGetFullTool.kt` | 43 |
| `JsonRpcResponse` | Class | `android/app/src/main/java/com/agentplatform/android/core/ws/JsonRpc.kt` | 21 |
| `Album` | Class | `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosListAlbumsTool.kt` | 64 |
| `decodeJsonRpc` | Function | `android/app/src/main/java/com/agentplatform/android/core/ws/JsonRpc.kt` | 55 |
| `Tool` | Interface | `android/app/src/main/java/com/agentplatform/android/core/tool/Tool.kt` | 11 |
| `onCreate` | Method | `android/app/src/main/java/com/agentplatform/android/service/AgentForegroundService.kt` | 55 |
| `register` | Method | `android/app/src/main/java/com/agentplatform/android/core/tool/ToolRegistry.kt` | 18 |
| `send` | Method | `android/app/src/main/java/com/agentplatform/android/core/ws/WsClient.kt` | 56 |
| `get` | Method | `android/app/src/main/java/com/agentplatform/android/core/tool/ToolRegistry.kt` | 22 |
| `execute` | Method | `android/app/src/main/java/com/agentplatform/android/core/tool/Tool.kt` | 26 |
| `execute` | Method | `android/app/src/main/java/com/agentplatform/android/tools/videos/VideosListRecentTool.kt` | 75 |
| `execute` | Method | `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosRecentScreenshotsTool.kt` | 65 |
| `execute` | Method | `android/app/src/main/java/com/agentplatform/android/tools/photos/PhotosListRecentTool.kt` | 92 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `OnWsMessage → Send` | cross_community | 4 |
| `OnWsMessage → JsonRpcResponse` | cross_community | 4 |
| `OnWsMessage → JsonRpcError` | cross_community | 4 |
| `OnWsMessage → Get` | cross_community | 4 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Exception | 7 calls |
| Data | 2 calls |
| Ws | 1 calls |
| Ai | 1 calls |

## How to Explore

1. `gitnexus_context({name: "decodeJsonRpc"})` — see callers and callees
2. `gitnexus_query({query: "photos"})` — find related execution flows
3. Read key files listed above for implementation details
