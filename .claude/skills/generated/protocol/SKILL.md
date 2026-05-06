---
name: protocol
description: "Skill for the Protocol area of agent-platform. 13 symbols across 5 files."
---

# Protocol

13 symbols | 5 files | Cohesion: 65%

## When to Use

- Working with code in `common/`
- Understanding how handleMessage, decode, validate work
- Modifying protocol-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `common/common-protocol/src/test/java/com/agentplatform/protocol/JsonRpcCodecTest.java` | roundtrip_request, roundtrip_response_error, decode_message_with_neither_id_nor_method_fails, roundtrip_response_success, roundtrip_notification (+1) |
| `common/common-protocol/src/test/java/com/agentplatform/protocol/ToolSchemaValidatorTest.java` | valid_args_pass, invalid_value_fails, missing_required_property_fails |
| `common/common-protocol/src/main/java/com/agentplatform/protocol/JsonRpcCodec.java` | decode, encode |
| `device-hub-service/src/test/java/com/agentplatform/hub/ws/DeviceWebSocketIntegrationTest.java` | handleMessage |
| `common/common-protocol/src/main/java/com/agentplatform/protocol/ToolSchemaValidator.java` | validate |

## Entry Points

Start here when exploring this area:

- **`handleMessage`** (Method) — `device-hub-service/src/test/java/com/agentplatform/hub/ws/DeviceWebSocketIntegrationTest.java:188`
- **`decode`** (Method) — `common/common-protocol/src/main/java/com/agentplatform/protocol/JsonRpcCodec.java:36`
- **`validate`** (Method) — `common/common-protocol/src/main/java/com/agentplatform/protocol/ToolSchemaValidator.java:22`
- **`encode`** (Method) — `common/common-protocol/src/main/java/com/agentplatform/protocol/JsonRpcCodec.java:28`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `handleMessage` | Method | `device-hub-service/src/test/java/com/agentplatform/hub/ws/DeviceWebSocketIntegrationTest.java` | 188 |
| `decode` | Method | `common/common-protocol/src/main/java/com/agentplatform/protocol/JsonRpcCodec.java` | 36 |
| `validate` | Method | `common/common-protocol/src/main/java/com/agentplatform/protocol/ToolSchemaValidator.java` | 22 |
| `encode` | Method | `common/common-protocol/src/main/java/com/agentplatform/protocol/JsonRpcCodec.java` | 28 |
| `roundtrip_request` | Method | `common/common-protocol/src/test/java/com/agentplatform/protocol/JsonRpcCodecTest.java` | 17 |
| `roundtrip_response_error` | Method | `common/common-protocol/src/test/java/com/agentplatform/protocol/JsonRpcCodecTest.java` | 48 |
| `decode_message_with_neither_id_nor_method_fails` | Method | `common/common-protocol/src/test/java/com/agentplatform/protocol/JsonRpcCodecTest.java` | 85 |
| `valid_args_pass` | Method | `common/common-protocol/src/test/java/com/agentplatform/protocol/ToolSchemaValidatorTest.java` | 13 |
| `invalid_value_fails` | Method | `common/common-protocol/src/test/java/com/agentplatform/protocol/ToolSchemaValidatorTest.java` | 28 |
| `missing_required_property_fails` | Method | `common/common-protocol/src/test/java/com/agentplatform/protocol/ToolSchemaValidatorTest.java` | 43 |
| `roundtrip_response_success` | Method | `common/common-protocol/src/test/java/com/agentplatform/protocol/JsonRpcCodecTest.java` | 34 |
| `roundtrip_notification` | Method | `common/common-protocol/src/test/java/com/agentplatform/protocol/JsonRpcCodecTest.java` | 61 |
| `encoded_request_omits_null_params` | Method | `common/common-protocol/src/test/java/com/agentplatform/protocol/JsonRpcCodecTest.java` | 76 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `HandleTextMessage → Encode` | cross_community | 4 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Exception | 3 calls |

## How to Explore

1. `gitnexus_context({name: "handleMessage"})` — see callers and callees
2. `gitnexus_query({query: "protocol"})` — find related execution flows
3. Read key files listed above for implementation details
