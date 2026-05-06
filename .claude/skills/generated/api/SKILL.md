---
name: api
description: "Skill for the Api area of agent-platform. 11 symbols across 1 files."
---

# Api

11 symbols | 1 files | Cohesion: 87%

## When to Use

- Working with code in `web/`
- Understanding how login, register, listDevices work
- Modifying api-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `web/src/api/client.ts` | ApiError, request, login, register, listDevices (+6) |

## Entry Points

Start here when exploring this area:

- **`login`** (Function) — `web/src/api/client.ts:39`
- **`register`** (Function) — `web/src/api/client.ts:44`
- **`listDevices`** (Function) — `web/src/api/client.ts:49`
- **`createEnrollment`** (Function) — `web/src/api/client.ts:52`
- **`listSessions`** (Function) — `web/src/api/client.ts:55`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `ApiError` | Class | `web/src/api/client.ts` | 2 |
| `login` | Function | `web/src/api/client.ts` | 39 |
| `register` | Function | `web/src/api/client.ts` | 44 |
| `listDevices` | Function | `web/src/api/client.ts` | 49 |
| `createEnrollment` | Function | `web/src/api/client.ts` | 52 |
| `listSessions` | Function | `web/src/api/client.ts` | 55 |
| `listMessages` | Function | `web/src/api/client.ts` | 58 |
| `createSession` | Function | `web/src/api/client.ts` | 61 |
| `getPreferences` | Function | `web/src/api/client.ts` | 64 |
| `updatePreferences` | Function | `web/src/api/client.ts` | 67 |
| `request` | Function | `web/src/api/client.ts` | 9 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Pages | 2 calls |

## How to Explore

1. `gitnexus_context({name: "login"})` — see callers and callees
2. `gitnexus_query({query: "api"})` — find related execution flows
3. Read key files listed above for implementation details
