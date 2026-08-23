# Public Conversation MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a versioned, authenticated text-and-audio conversation API that resolves a published Agent in Java and streams runtime events from Python without using MQTT.

**Architecture:** `manager-api` remains the control-plane entry point. It authenticates a user token or scoped API key, resolves one published Agent version and authorized resource overrides, and signs a short-lived runtime token. Python validates that token, creates an isolated external session, and emits ordered ASR/LLM/TTS events over WebSocket. Device sessions and MQTT remain on their existing path.

**Tech Stack:** Spring Boot, MyBatis-Plus, Shiro user context, Java HMAC-SHA256, Python `aiohttp`, asyncio, existing ASR/LLM/TTS providers, pytest and Maven.

---

## File map

The Java control-plane files own authentication, Agent authorization, session records, and the public REST contract. Python files own token verification, session state, provider calls, event sequencing, cancellation, and the WebSocket transport. Protocol tests live beside each owner; one cross-layer contract fixture proves the JSON shape without requiring live provider calls.

### Task 1: Define the shared protocol contract

**Files:**
- Create: `server/main/xiaozhi-server/core/public_conversation/protocol.py`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/dto/PublicConversationCreateDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/vo/PublicConversationSessionVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/vo/PublicConversationEventVO.java`
- Test: `server/main/xiaozhi-server/tests/test_public_conversation_protocol.py`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationProtocolTest.java`

- [ ] **Step 1: Write the failing protocol tests**

The Python test must assert that a session-ready event contains `type`, `conversation_id`, `agent_version`, `sequence`, and `occurred_at`; a turn event contains `turn_id`; audio events contain `mime_type`, `data`, and `sequence`; and an error event contains only `code`, `message`, and `retryable`.

The Java test must assert that the create DTO accepts `agentId`, `inputModes`, `outputModes`, optional `voiceId`, and `modelOverrides`, while rejecting a missing `agentId` and unsupported input or output modes.

- [ ] **Step 2: Run the tests and confirm missing protocol types**

```bash
cd server/main/xiaozhi-server
/Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q tests/test_public_conversation_protocol.py
cd ../../manager-api
mvn -q -Dtest=PublicConversationProtocolTest test
```

Expected: both commands fail because the new protocol classes do not exist.

- [ ] **Step 3: Implement the minimal protocol types**

Python `protocol.py` must define frozen dataclasses for `ConversationEvent`, `TextTurnInput`, `AudioTurnInput`, and `RuntimeTokenClaims`. `ConversationEvent.to_dict()` must emit snake-case JSON keys used by the runtime wire contract and must truncate human-readable summaries to 400 characters. It must reject non-positive event sequence numbers.

Java DTO validation must use the repository's existing Jakarta validation conventions. The DTO must normalize absent override maps to an empty map in the service layer; it must not carry provider credentials.

- [ ] **Step 4: Run both protocol test suites**

Expected: all protocol tests pass with no secret fields in serialized output.

- [ ] **Step 5: Commit the protocol contract**

```bash
git add server/main/xiaozhi-server/core/public_conversation server/main/xiaozhi-server/tests/test_public_conversation_protocol.py server/main/manager-api/src/main/java/xiaozhi/modules/conversation server/main/manager-api/src/test/java/xiaozhi/modules/conversation
git commit -m "feat: define public conversation protocol"
```

### Task 2: Sign and verify short-lived runtime tokens

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/ConversationRuntimeTokenService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/HmacConversationRuntimeTokenServiceImpl.java`
- Create: `server/main/xiaozhi-server/core/public_conversation/token.py`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/HmacConversationRuntimeTokenServiceTest.java`
- Test: `server/main/xiaozhi-server/tests/test_public_conversation_token.py`

- [ ] **Step 1: Write token red tests**

Cover valid claims, expiration, wrong signature, wrong token version, wrong audience, missing conversation ID, and a token that tries to include a provider secret. The expected verifier result must expose only `conversation_id`, `subject`, `agent_id`, `agent_version`, `scopes`, `input_modes`, `output_modes`, `issued_at`, and `expires_at`.

- [ ] **Step 2: Run the token tests and confirm they fail**

```bash
cd server/main/xiaozhi-server
/Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q tests/test_public_conversation_token.py
cd ../../manager-api
mvn -q -Dtest=HmacConversationRuntimeTokenServiceTest test
```

- [ ] **Step 3: Implement one versioned HMAC format**

Use a URL-safe base64 payload and signature with `v1.<payload>.<signature>`. Claims must include `aud=public-conversation`, a 15-minute expiry, and a key ID. Java signs with the existing `Constant.SERVER_SECRET`; Python receives the same secret through the existing internal runtime configuration and compares signatures with a constant-time function. Neither side logs the token.

- [ ] **Step 4: Run token tests and verify secret redaction**

Expected: valid tokens verify, expired or altered tokens fail with stable error codes, and serialized claims never contain provider keys or API Key material.

- [ ] **Step 5: Commit token support**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service server/main/manager-api/src/test/java/xiaozhi/modules/conversation server/main/xiaozhi-server/core/public_conversation/token.py server/main/xiaozhi-server/tests/test_public_conversation_token.py
git commit -m "feat: add signed conversation runtime tokens"
```

### Task 3: Create an authorized conversation session in manager-api

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/controller/PublicConversationController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/PublicConversationService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/PublicConversationServiceImpl.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/vo/PublicConversationResourceVO.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationServiceTest.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationControllerTest.java`

- [ ] **Step 1: Write authorization tests**

Test that an authenticated user can create a session for an owned Agent with an active version; an unknown Agent, unpublished version, unavailable model, unavailable voice, or unauthorized device scope is rejected; APP callers cannot send model overrides; and a scoped API Key may override only a voice resource in its allowed set. Assert that the returned session contains no secret field.

- [ ] **Step 2: Run tests to confirm the service and controller are missing**

```bash
cd server/main/manager-api
mvn -q -Dtest=PublicConversationServiceTest,PublicConversationControllerTest test
```

- [ ] **Step 3: Resolve the Agent snapshot and effective resources**

Use existing `AgentService`, snapshot activation data, `CompanionEffectiveModelService`, and `TimbreService`. The service must select the Agent active version, copy only non-secret runtime metadata into `RuntimeTokenClaims`, enforce first-party versus API-Key scopes, and reject all unknown override keys. Persist only an in-memory session record for the MVP with a 15-minute expiry; do not add database tables until usage and revocation requirements are proven.

- [ ] **Step 4: Add the REST endpoint**

Expose `POST /api/v1/conversations` under a new controller. Reuse `SecurityUser` for first-party users and add an API-Key authentication resolver that returns a subject and scopes without changing existing admin routes. Return `PublicConversationSessionVO` with `conversationId`, `agentId`, `agentVersion`, `streamUrl`, `runtimeToken`, `expiresAt`, `inputModes`, and `outputModes`.

- [ ] **Step 5: Run service/controller tests and manager-api targeted build**

```bash
cd server/main/manager-api
mvn -q -Dtest=PublicConversationServiceTest,PublicConversationControllerTest test
mvn -q -DskipTests compile
```

- [ ] **Step 6: Commit session creation**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/conversation server/main/manager-api/src/test/java/xiaozhi/modules/conversation
git commit -m "feat: create authorized public conversation sessions"
```

### Task 4: Implement Python external session and WebSocket stream

**Files:**
- Create: `server/main/xiaozhi-server/core/public_conversation/session.py`
- Create: `server/main/xiaozhi-server/core/public_conversation/service.py`
- Create: `server/main/xiaozhi-server/core/api/public_conversation_handler.py`
- Modify: `server/main/xiaozhi-server/core/http_server.py`
- Test: `server/main/xiaozhi-server/tests/test_public_conversation_session.py`
- Test: `server/main/xiaozhi-server/tests/test_public_conversation_http.py`

- [ ] **Step 1: Write the red session tests**

Use fake ASR, LLM, and TTS providers. Assert text input emits `session.ready`, `turn.started`, `llm.delta`, and `turn.completed` in increasing sequence order. Assert audio input emits `asr.final` before `llm.delta` and `tts.audio`, rejects duplicate `request_id`, and sends exactly one `turn.cancelled` after cancellation. Assert an expired runtime token is rejected before creating provider instances.

- [ ] **Step 2: Run the session tests and confirm missing runtime types**

```bash
cd server/main/xiaozhi-server
/Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q tests/test_public_conversation_session.py tests/test_public_conversation_http.py
```

- [ ] **Step 3: Implement an isolated session state machine**

`PublicConversationSession` must own one `conversation_id`, one Agent runtime bundle, one event sequence, one active `turn_id`, an idempotency set, and cancellation state. It must never use an ESP32 `device_id`, MQTT session ID, or global provider instance. Provider failures map to stable error codes and always finish the turn with a terminal event.

- [ ] **Step 4: Add the aiohttp WebSocket route**

Register `/api/v1/conversations/{conversation_id}/stream` in `SimpleHttpServer.create_app`. Validate the runtime token before upgrading or immediately close with an unauthorized WebSocket error. Accept JSON text and turn-control frames plus bounded binary audio frames. Send events as JSON text frames and `tts.audio` as JSON metadata plus base64 for the first MVP; add binary audio frames only after the event contract is stable.

- [ ] **Step 5: Run Python session, HTTP, and existing runtime tests**

```bash
cd server/main/xiaozhi-server
/Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q tests/test_public_conversation_session.py tests/test_public_conversation_http.py tests/test_huoshan_tts_buffer.py tests/test_debug_event_instrumentation.py
```

- [ ] **Step 6: Commit the Python stream**

```bash
git add server/main/xiaozhi-server/core/public_conversation server/main/xiaozhi-server/core/api/public_conversation_handler.py server/main/xiaozhi-server/core/http_server.py server/main/xiaozhi-server/tests/test_public_conversation_session.py server/main/xiaozhi-server/tests/test_public_conversation_http.py
git commit -m "feat: stream public conversation runtime events"
```

### Task 5: Add resource discovery and cross-layer contract verification

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/controller/PublicConversationResourceController.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationResourceControllerTest.java`
- Create: `server/main/xiaozhi-server/tests/test_public_conversation_cross_layer.py`
- Modify: `docs/api/xiaozhi-server-openapi.json`
- Modify: `docs/chain-baseline-2026-08-23.md`

- [ ] **Step 1: Write resource and cross-layer contract tests**

Assert that Agent, model, voice, and device list responses contain only public metadata; Python accepts a Java-issued token fixture; an event sequence is valid across a text turn; and a token for Agent A cannot open a session for Agent B.

- [ ] **Step 2: Implement read-only resource endpoints**

Expose `/api/v1/agents`, `/api/v1/models`, `/api/v1/voices`, and `/api/v1/devices` by adapting existing user-scoped services. Reuse existing page parsing and ownership rules. API Keys use scope filters; ordinary users never receive admin-only model secrets or device credentials.

- [ ] **Step 3: Add the OpenAPI contract and redaction checks**

Document the REST session and resource endpoints plus WebSocket event schemas. Add a test that fails if serialized examples contain `api_key`, `access_token`, `secret`, `password`, or `authorization` values.

- [ ] **Step 4: Run the full MVP verification**

```bash
cd server/main/manager-api
mvn -q -Dtest='xiaozhi.modules.conversation.**' test
cd /Users/xiaox/WorkShop/ai-plush-companion-public/server/main/xiaozhi-server
/Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q tests/test_public_conversation_*.py tests/test_huoshan_tts_buffer.py tests/test_debug_event_instrumentation.py
cd /Users/xiaox/WorkShop/ai-plush-companion-public
openspec validate --changes --json
git diff --check
```

Expected: all new contract tests pass, existing device/MQTT tests remain green, and no secret appears in OpenAPI examples or runtime events.

- [ ] **Step 5: Commit the MVP contract evidence**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/conversation server/main/manager-api/src/test/java/xiaozhi/modules/conversation server/main/xiaozhi-server/tests/test_public_conversation_cross_layer.py docs/api/xiaozhi-server-openapi.json docs/chain-baseline-2026-08-23.md
git commit -m "feat: verify public conversation api contract"
```

## Exit criteria

The MVP is complete when a first-party user and a scoped API Key can each create an authorized session, a text turn streams ordered LLM events, an audio turn streams ASR and TTS events, cancellation and expiry are deterministic, resource lists are independently callable, two Agent sessions are isolated, and existing MQTT hardware tests and real-device baseline remain green.
