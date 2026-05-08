---
name: security
description: "Skill for the Security area of agent-platform. 20 symbols across 11 files."
---

# Security

20 symbols | 11 files | Cohesion: 75%

## When to Use

- Working with code in `common/`
- Understanding how TrustedHeaderAuthFilter, JwtAuthenticationFilter, AbstractAuthFilter work
- Modifying security-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `common/common-security/src/test/java/com/agentplatform/security/JwtUtilTest.java` | expired_token_rejected, wrong_secret_rejected, wrong_issuer_rejected, user_token_roundtrip, device_token_roundtrip |
| `common/common-security/src/main/java/com/agentplatform/security/AbstractAuthFilter.java` | AbstractAuthFilter, doFilter, doFilterInternal |
| `common/common-security/src/main/java/com/agentplatform/security/Principal.java` | isUser, userIdAsUuid, subjectAsUuid |
| `common/common-security/src/main/java/com/agentplatform/security/JwtUtil.java` | issueUserToken, verify |
| `auth-service/src/main/java/com/agentplatform/auth/service/JwtService.java` | verifyAndCheckRevocation |
| `auth-service/src/main/java/com/agentplatform/auth/controller/InternalAuthController.java` | verify |
| `common/common-security/src/main/java/com/agentplatform/security/TrustedHeaderAuthFilter.java` | TrustedHeaderAuthFilter |
| `common/common-security/src/main/java/com/agentplatform/security/JwtAuthenticationFilter.java` | JwtAuthenticationFilter |
| `chat-service/src/main/java/com/agentplatform/chat/filter/ChatAuthFilter.java` | ChatAuthFilter |
| `auth-service/src/main/java/com/agentplatform/auth/filter/PathBasedJwtFilter.java` | PathBasedJwtFilter |

## Entry Points

Start here when exploring this area:

- **`TrustedHeaderAuthFilter`** (Class) — `common/common-security/src/main/java/com/agentplatform/security/TrustedHeaderAuthFilter.java:19`
- **`JwtAuthenticationFilter`** (Class) — `common/common-security/src/main/java/com/agentplatform/security/JwtAuthenticationFilter.java:23`
- **`AbstractAuthFilter`** (Class) — `common/common-security/src/main/java/com/agentplatform/security/AbstractAuthFilter.java:16`
- **`ChatAuthFilter`** (Class) — `chat-service/src/main/java/com/agentplatform/chat/filter/ChatAuthFilter.java:26`
- **`PathBasedJwtFilter`** (Class) — `auth-service/src/main/java/com/agentplatform/auth/filter/PathBasedJwtFilter.java:28`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `TrustedHeaderAuthFilter` | Class | `common/common-security/src/main/java/com/agentplatform/security/TrustedHeaderAuthFilter.java` | 19 |
| `JwtAuthenticationFilter` | Class | `common/common-security/src/main/java/com/agentplatform/security/JwtAuthenticationFilter.java` | 23 |
| `AbstractAuthFilter` | Class | `common/common-security/src/main/java/com/agentplatform/security/AbstractAuthFilter.java` | 16 |
| `ChatAuthFilter` | Class | `chat-service/src/main/java/com/agentplatform/chat/filter/ChatAuthFilter.java` | 26 |
| `PathBasedJwtFilter` | Class | `auth-service/src/main/java/com/agentplatform/auth/filter/PathBasedJwtFilter.java` | 28 |
| `AgentAuthFilter` | Class | `agent-service/src/main/java/com/agentplatform/agent/filter/AgentAuthFilter.java` | 31 |
| `issueUserToken` | Method | `common/common-security/src/main/java/com/agentplatform/security/JwtUtil.java` | 41 |
| `verify` | Method | `common/common-security/src/main/java/com/agentplatform/security/JwtUtil.java` | 56 |
| `verifyAndCheckRevocation` | Method | `auth-service/src/main/java/com/agentplatform/auth/service/JwtService.java` | 43 |
| `verify` | Method | `auth-service/src/main/java/com/agentplatform/auth/controller/InternalAuthController.java` | 33 |
| `isUser` | Method | `common/common-security/src/main/java/com/agentplatform/security/Principal.java` | 20 |
| `userIdAsUuid` | Method | `common/common-security/src/main/java/com/agentplatform/security/Principal.java` | 23 |
| `subjectAsUuid` | Method | `common/common-security/src/main/java/com/agentplatform/security/Principal.java` | 24 |
| `doFilter` | Method | `common/common-security/src/main/java/com/agentplatform/security/AbstractAuthFilter.java` | 18 |
| `doFilterInternal` | Method | `common/common-security/src/main/java/com/agentplatform/security/AbstractAuthFilter.java` | 29 |
| `expired_token_rejected` | Method | `common/common-security/src/test/java/com/agentplatform/security/JwtUtilTest.java` | 44 |
| `wrong_secret_rejected` | Method | `common/common-security/src/test/java/com/agentplatform/security/JwtUtilTest.java` | 52 |
| `wrong_issuer_rejected` | Method | `common/common-security/src/test/java/com/agentplatform/security/JwtUtilTest.java` | 66 |
| `user_token_roundtrip` | Method | `common/common-security/src/test/java/com/agentplatform/security/JwtUtilTest.java` | 20 |
| `device_token_roundtrip` | Method | `common/common-security/src/test/java/com/agentplatform/security/JwtUtilTest.java` | 32 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Verify → GetId` | cross_community | 6 |
| `Verify → GetUserId` | cross_community | 6 |
| `Verify → GetTitle` | cross_community | 6 |
| `Verify → GetCreatedAt` | cross_community | 6 |
| `DoFilterInternal → GetId` | cross_community | 6 |
| `DoFilterInternal → GetUserId` | cross_community | 6 |
| `DoFilterInternal → GetTitle` | cross_community | 6 |
| `DoFilterInternal → GetCreatedAt` | cross_community | 6 |
| `DoFilterInternal → GetId` | cross_community | 6 |
| `DoFilterInternal → GetUserId` | cross_community | 6 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Ws | 3 calls |
| Filter | 1 calls |

## How to Explore

1. `gitnexus_context({name: "TrustedHeaderAuthFilter"})` — see callers and callees
2. `gitnexus_query({query: "security"})` — find related execution flows
3. Read key files listed above for implementation details
