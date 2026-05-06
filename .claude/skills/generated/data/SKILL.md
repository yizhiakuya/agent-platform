---
name: data
description: "Skill for the Data area of agent-platform. 7 symbols across 2 files."
---

# Data

7 symbols | 2 files | Cohesion: 75%

## When to Use

- Working with code in `android/`
- Understanding how AppPrefs, onCreate, isBound work
- Modifying data-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/java/com/agentplatform/android/data/AppPrefs.kt` | AppPrefs, isBound, save, clear |
| `android/app/src/main/java/com/agentplatform/android/ui/MainActivity.kt` | onCreate, startAgentService, stopAgentService |

## Entry Points

Start here when exploring this area:

- **`AppPrefs`** (Class) — `android/app/src/main/java/com/agentplatform/android/data/AppPrefs.kt:12`
- **`onCreate`** (Method) — `android/app/src/main/java/com/agentplatform/android/ui/MainActivity.kt:20`
- **`isBound`** (Method) — `android/app/src/main/java/com/agentplatform/android/data/AppPrefs.kt:28`
- **`save`** (Method) — `android/app/src/main/java/com/agentplatform/android/data/AppPrefs.kt:30`
- **`clear`** (Method) — `android/app/src/main/java/com/agentplatform/android/data/AppPrefs.kt:38`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `AppPrefs` | Class | `android/app/src/main/java/com/agentplatform/android/data/AppPrefs.kt` | 12 |
| `onCreate` | Method | `android/app/src/main/java/com/agentplatform/android/ui/MainActivity.kt` | 20 |
| `isBound` | Method | `android/app/src/main/java/com/agentplatform/android/data/AppPrefs.kt` | 28 |
| `save` | Method | `android/app/src/main/java/com/agentplatform/android/data/AppPrefs.kt` | 30 |
| `clear` | Method | `android/app/src/main/java/com/agentplatform/android/data/AppPrefs.kt` | 38 |
| `startAgentService` | Method | `android/app/src/main/java/com/agentplatform/android/ui/MainActivity.kt` | 51 |
| `stopAgentService` | Method | `android/app/src/main/java/com/agentplatform/android/ui/MainActivity.kt` | 58 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `OnCreate → IsGranted` | cross_community | 4 |
| `OnCreate → StatusCard` | cross_community | 3 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Ui | 2 calls |

## How to Explore

1. `gitnexus_context({name: "AppPrefs"})` — see callers and callees
2. `gitnexus_query({query: "data"})` — find related execution flows
3. Read key files listed above for implementation details
