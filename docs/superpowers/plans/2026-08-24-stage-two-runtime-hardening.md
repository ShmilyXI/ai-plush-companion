# Stage Two Runtime Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make component readiness, MQTT bridge liveness, and cross-layer failure reporting deterministic after the successful phase-one hardware baseline.

**Architecture:** Keep the existing Python connection model and MQTT device protocol, but make readiness an explicit barrier: TTS and ASR channel coroutines must finish before `components_ready_event` is set. The gateway must expose a boolean liveness result and close the MQTT session when a remote Python bridge dies. Existing debug-event types remain the correlation contract for VAD, ASR, LLM, TTS, cancellation, and connection cleanup.

**Tech Stack:** Python asyncio and pytest, Node.js `ws`/MQTT gateway, existing debug-event reporter, ESP32 MQTT+UDP bridge.

---

## Task 1: Make component readiness wait for channel initialization

**Files:**
- Modify: `server/main/xiaozhi-server/core/connection.py:722-772`
- Test: `server/main/xiaozhi-server/tests/test_companion_conversation.py`

- [ ] **Step 1: Add a failing readiness-barrier test**

Extend `test_worker_thread_notifies_component_readiness_on_event_loop` with a fake TTS and ASR whose `open_audio_channels` coroutines each wait on an `asyncio.Event`. Start `_initialize_components` in the worker thread, assert `components_ready_event` remains unset while either channel event is unset, then release both events and assert the worker completes and readiness becomes set.

The test must patch `asyncio.run_coroutine_threadsafe` only through the real loop thread and must assert that both `open_audio_channels` calls completed before readiness. It must not sleep for an arbitrary readiness delay.

- [ ] **Step 2: Run the focused test and confirm the current race**

Run:

```bash
cd server/main/xiaozhi-server
/Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q tests/test_companion_conversation.py::CompanionConversationTest::test_worker_thread_waits_for_audio_channels_before_readiness
```

Expected: FAIL because `_initialize_components` currently schedules TTS and ASR coroutines without waiting for their futures before setting `components_ready_event`.

- [ ] **Step 3: Wait for both initialization futures in production code**

Replace the fire-and-forget calls with a small helper inside `ConnectionHandler._initialize_components`:

```python
def wait_for_component(coroutine):
    future = asyncio.run_coroutine_threadsafe(coroutine, self.loop)
    future.result(timeout=float(self.config.get("component_init_timeout", 15)))

wait_for_component(self.tts.open_audio_channels(self))
wait_for_component(self.asr.open_audio_channels(self))
```

Keep the existing worker-thread execution model and readiness event. If either future fails or times out, the outer `try` must log the initialization failure and leave `components_ready_event` unset so audio cannot enter a half-initialized connection.

- [ ] **Step 4: Run the focused test and the existing connection tests**

Run:

```bash
cd server/main/xiaozhi-server
/Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q tests/test_companion_conversation.py::CompanionConversationTest::test_worker_thread_waits_for_audio_channels_before_readiness tests/test_companion_conversation.py::CompanionConversationTest::test_worker_thread_notifies_component_readiness_on_event_loop
```

Expected: both readiness tests pass. The existing worker test must still complete without a live provider.

- [ ] **Step 5: Commit the initialization barrier**

```bash
git add server/main/xiaozhi-server/core/connection.py server/main/xiaozhi-server/tests/test_companion_conversation.py
git commit -m "fix: wait for audio channels before connection readiness"
```

## Task 2: Normalize MQTT bridge liveness and preserve lifecycle semantics

**Files:**
- Modify: `mqtt-gateway/app.js:198-205,628-630`
- Test: `mqtt-gateway/tests/test_hello_feature_forwarding.py`

- [ ] **Step 1: Add a failing liveness contract test**

Add a source-level contract test that requires `WebSocketBridge.isAlive()` and `MQTTConnection.isAlive()` to return `false` when no bridge or WebSocket exists, never JavaScript `null`. The test must also retain the existing remote-close contract from `test_remote_bridge_drop_closes_stale_mqtt_session`.

- [ ] **Step 2: Run the test and confirm the current null result**

Run:

```bash
cd mqtt-gateway
/Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q tests/test_hello_feature_forwarding.py::HelloFeatureForwardingTest::test_liveness_is_boolean
```

Expected: FAIL because both current methods return `this.wsClient && ...` or `this.bridge && ...`, which yields `null` when the object is absent.

- [ ] **Step 3: Return explicit booleans**

Change the methods to:

```javascript
isAlive() {
    return Boolean(this.wsClient && this.wsClient.readyState === WebSocket.OPEN);
}
```

and:

```javascript
isAlive() {
    return Boolean(this.bridge && this.bridge.isAlive());
}
```

Do not change the remote-close distinction or call-mode behavior implemented in `1f0e886`.

- [ ] **Step 4: Run all gateway tests and syntax validation**

```bash
cd mqtt-gateway
/Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q
node --check app.js
```

Expected: all gateway tests pass and Node syntax validation exits 0.

- [ ] **Step 5: Commit the liveness contract**

```bash
git add mqtt-gateway/app.js mqtt-gateway/tests/test_hello_feature_forwarding.py
git commit -m "fix: normalize gateway liveness to boolean"
```

## Task 3: Verify cross-layer event correlation after hardening

**Files:**
- Read: `server/main/xiaozhi-server/core/providers/vad/silero.py`
- Read: `server/main/xiaozhi-server/core/providers/asr/base.py`
- Read: `server/main/xiaozhi-server/core/connection.py`
- Read: `server/main/xiaozhi-server/core/providers/tts/base.py`
- Modify: `docs/chain-baseline-2026-08-23.md`

- [ ] **Step 1: Run the event instrumentation contract tests**

```bash
cd server/main/xiaozhi-server
/Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q tests/test_debug_event_instrumentation.py tests/test_audio_latency_events.py
```

Expected: VAD transitions, ASR completion/failure, LLM first-visible, TTS first-audio/completion/failure, and cancellation event contracts pass.

- [ ] **Step 2: Check event identifiers against the real session**

Use the post-fix session `4e7bf969-4d2e-4577-ad6a-1cfb1831c1c3` in the committed baseline report. Record which events carry the session ID and sentence ID, and mark fields that are not emitted at the VAD or raw audio boundary. Do not copy prompts, credentials, or raw audio.

- [ ] **Step 3: Add the hardening result to the baseline report**

Document the readiness barrier, boolean liveness contract, real successful session, real TTS failure session, and the remaining VAD/barge-in observation gaps. Keep the report explicit about what was measured versus inferred.

- [ ] **Step 4: Run the combined phase-two verification**

```bash
cd mqtt-gateway && /Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q
cd ../server/main/xiaozhi-server && /Users/xiaox/WorkShop/ai-plush-companion-public/.venv312/bin/python -m pytest -q tests/test_companion_conversation.py tests/test_debug_event_instrumentation.py tests/test_audio_latency_events.py tests/test_huoshan_tts_buffer.py tests/test_huoshan_double_stream.py
cd ../../.. && node --check mqtt-gateway/app.js && git diff --check
```

Expected: all commands exit 0. Existing unrelated worktree changes must remain unstaged.

- [ ] **Step 5: Commit the phase-two evidence**

```bash
git add docs/chain-baseline-2026-08-23.md
git commit -m "docs: record runtime hardening verification"
```

## Exit criteria

This plan is complete when readiness cannot be observed before TTS and ASR channels finish initialization, gateway status APIs return booleans, remote bridge loss closes the stale MQTT session, the focused Python and gateway tests pass, and the baseline report records both the successful post-fix hardware session and the remaining VAD/barge-in gaps.
