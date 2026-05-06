---
name: pages
description: "Skill for the Pages area of agent-platform. 17 symbols across 6 files."
---

# Pages

17 symbols | 6 files | Cohesion: 88%

## When to Use

- Working with code in `web/`
- Understanding how getToken, streamChat, ChatPage work
- Modifying pages-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `web/src/pages/ChatPage.tsx` | ChatPage, send, Bubble, ToolResult, ThumbGrid (+2) |
| `web/src/lib/auth.ts` | getToken, setToken, isAuthed |
| `web/src/App.tsx` | RequireAuth, Shell, App |
| `web/src/lib/chatStore.tsx` | useChatStore, ChatStoreProvider |
| `web/src/api/sse.ts` | streamChat |
| `web/src/pages/LoginPage.tsx` | submit |

## Entry Points

Start here when exploring this area:

- **`getToken`** (Function) — `web/src/lib/auth.ts:5`
- **`streamChat`** (Function) — `web/src/api/sse.ts:22`
- **`ChatPage`** (Function) — `web/src/pages/ChatPage.tsx:4`
- **`send`** (Function) — `web/src/pages/ChatPage.tsx:41`
- **`useChatStore`** (Function) — `web/src/lib/chatStore.tsx:46`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `getToken` | Function | `web/src/lib/auth.ts` | 5 |
| `streamChat` | Function | `web/src/api/sse.ts` | 22 |
| `ChatPage` | Function | `web/src/pages/ChatPage.tsx` | 4 |
| `send` | Function | `web/src/pages/ChatPage.tsx` | 41 |
| `useChatStore` | Function | `web/src/lib/chatStore.tsx` | 46 |
| `setToken` | Function | `web/src/lib/auth.ts` | 9 |
| `isAuthed` | Function | `web/src/lib/auth.ts` | 14 |
| `App` | Function | `web/src/App.tsx` | 36 |
| `submit` | Function | `web/src/pages/LoginPage.tsx` | 13 |
| `ChatStoreProvider` | Function | `web/src/lib/chatStore.tsx` | 25 |
| `handleStop` | Function | `web/src/pages/ChatPage.tsx` | 18 |
| `onKey` | Function | `web/src/pages/ChatPage.tsx` | 30 |
| `Bubble` | Function | `web/src/pages/ChatPage.tsx` | 152 |
| `ToolResult` | Function | `web/src/pages/ChatPage.tsx` | 191 |
| `ThumbGrid` | Function | `web/src/pages/ChatPage.tsx` | 289 |
| `RequireAuth` | Function | `web/src/App.tsx` | 8 |
| `Shell` | Function | `web/src/App.tsx` | 12 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `ChatPage → ThumbGrid` | intra_community | 4 |
| `ChatPage → GetToken` | intra_community | 4 |
| `App → GetToken` | cross_community | 4 |

## How to Explore

1. `gitnexus_context({name: "getToken"})` — see callers and callees
2. `gitnexus_query({query: "pages"})` — find related execution flows
3. Read key files listed above for implementation details
