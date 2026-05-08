---
name: ui
description: "Skill for the Ui area of agent-platform. 14 symbols across 3 files."
---

# Ui

14 symbols | 3 files | Cohesion: 68%

## When to Use

- Working with code in `android/`
- Understanding how onSuccess, EnrollScreen, runBind work
- Modifying ui-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/java/com/agentplatform/android/ui/BoundScreen.kt` | BoundScreen, StatusCard, InfoCard, openAppSettings, isGranted (+3) |
| `android/app/src/main/java/com/agentplatform/android/ui/EnrollScreen.kt` | BindResult, EnrollScreen, runBind, parseEnrollPayload, redeem |
| `web/src/pages/DevicesPage.tsx` | onSuccess |

## Entry Points

Start here when exploring this area:

- **`onSuccess`** (Function) — `web/src/pages/DevicesPage.tsx:12`
- **`EnrollScreen`** (Function) — `android/app/src/main/java/com/agentplatform/android/ui/EnrollScreen.kt:43`
- **`runBind`** (Function) — `android/app/src/main/java/com/agentplatform/android/ui/EnrollScreen.kt:52`
- **`BoundScreen`** (Function) — `android/app/src/main/java/com/agentplatform/android/ui/BoundScreen.kt:49`
- **`isGranted`** (Function) — `android/app/src/main/java/com/agentplatform/android/ui/BoundScreen.kt:55`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `BindResult` | Class | `android/app/src/main/java/com/agentplatform/android/ui/EnrollScreen.kt` | 41 |
| `onSuccess` | Function | `web/src/pages/DevicesPage.tsx` | 12 |
| `EnrollScreen` | Function | `android/app/src/main/java/com/agentplatform/android/ui/EnrollScreen.kt` | 43 |
| `runBind` | Function | `android/app/src/main/java/com/agentplatform/android/ui/EnrollScreen.kt` | 52 |
| `BoundScreen` | Function | `android/app/src/main/java/com/agentplatform/android/ui/BoundScreen.kt` | 49 |
| `isGranted` | Function | `android/app/src/main/java/com/agentplatform/android/ui/BoundScreen.kt` | 55 |
| `checkNotif` | Function | `android/app/src/main/java/com/agentplatform/android/ui/BoundScreen.kt` | 58 |
| `checkPhotos` | Function | `android/app/src/main/java/com/agentplatform/android/ui/BoundScreen.kt` | 59 |
| `checkVideos` | Function | `android/app/src/main/java/com/agentplatform/android/ui/BoundScreen.kt` | 63 |
| `parseEnrollPayload` | Function | `android/app/src/main/java/com/agentplatform/android/ui/EnrollScreen.kt` | 145 |
| `redeem` | Function | `android/app/src/main/java/com/agentplatform/android/ui/EnrollScreen.kt` | 155 |
| `StatusCard` | Function | `android/app/src/main/java/com/agentplatform/android/ui/BoundScreen.kt` | 155 |
| `InfoCard` | Function | `android/app/src/main/java/com/agentplatform/android/ui/BoundScreen.kt` | 175 |
| `openAppSettings` | Function | `android/app/src/main/java/com/agentplatform/android/ui/BoundScreen.kt` | 204 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `OnCreate → IsGranted` | cross_community | 4 |
| `EnrollScreen → SafeUri` | cross_community | 4 |
| `EnrollScreen → Get` | cross_community | 4 |
| `OnCreate → StatusCard` | cross_community | 3 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Ai | 3 calls |

## How to Explore

1. `gitnexus_context({name: "onSuccess"})` — see callers and callees
2. `gitnexus_query({query: "ui"})` — find related execution flows
3. Read key files listed above for implementation details
