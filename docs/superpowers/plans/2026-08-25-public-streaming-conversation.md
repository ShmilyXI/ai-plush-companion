# Public Streaming Conversation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a versioned public streaming WebSocket mode with continuous PCM input, ASR partial/final events, streamed LLM text, first-byte TTS playback, cancellation, and legacy protocol compatibility.

**Architecture:** Keep the current `/api/v1/conversations/{id}/stream` turn protocol unchanged. Add a streaming mode on the same authenticated socket selected by `stream.start`; a dedicated Python stream session owns the ASR stream, VAD endpointing, per-turn LLM/tool loop, TTS chunk emission, cancellation, and backpressure. The realtime browser tab opts into this mode while the ordinary chat tab and existing clients remain on the legacy turn path.

**Tech Stack:** Python asyncio/aiohttp, existing ASR provider interfaces, OpenAI-compatible LLM providers, existing TTS providers, Java runtime token/bundle, browser WebSocket and Web Audio, pytest, JUnit 5, Node test runner, Playwright.

---

### Task 1: Define streaming protocol and frame validation

**Files:**
- Modify: `server/main/xiaozhi-server/core/public_conversation/protocol.py`
- Modify: `server/main/xiaozhi-server/core/api/public_conversation_handler.py`
- Create: `server/main/xiaozhi-server/tests/test_public_conversation_stream_protocol.py`

- [ ] **Step 1: Write failing protocol tests**

```python
def test_stream_start_requires_pcm16_format_and_sample_rate():
    assert StreamStartInput.from_payload({
        "type": "stream.start", "request_id": "r1",
        "audio": {"format": "pcm_s16le", "sample_rate": 16000, "channels": 1},
    }).sample_rate == 16000
    with pytest.raises(ValueError, match="sample_rate"):
        StreamStartInput.from_payload({"type": "stream.start", "request_id": "r1", "audio": {"sample_rate": 8000}})

def test_stream_audio_end_is_idempotent_and_stop_is_valid():
    assert StreamControlFrame.from_payload({"type": "stream.audio.end"}).type == "stream.audio.end"
    assert StreamControlFrame.from_payload({"type": "stream.stop"}).type == "stream.stop"
```

- [ ] **Step 2: Run the test and verify RED**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q tests/test_public_conversation_stream_protocol.py
```

Expected: FAIL because streaming control frame types do not exist.

- [ ] **Step 3: Add typed stream control inputs and event details**

Add `StreamStartInput`, `StreamControlFrame`, `MAX_STREAM_FRAME_BYTES`, and validation for request IDs, `pcm_s16le`, 16000 Hz, mono channels, duplicate start, unknown controls, binary before start, and oversized binary frames. Add event helpers for `stream.ready`, `asr.partial`, `tts.audio.chunk`, and `turn.cancelled` while retaining existing `ConversationEvent` serialization and secret filtering.

- [ ] **Step 4: Route stream controls without changing legacy behavior**

In the handler, keep the existing `turn.audio.start` branch unchanged. Add a per-connection `stream_mode` flag. Before `stream.start`, binary frames return `stream_not_started`; after `stream.stop`, stop accepting new frames and close normally. `stream.audio.end` is forwarded to the streaming session and repeated end frames do not create another turn.

- [ ] **Step 5: Run protocol and legacy HTTP tests**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q \
  tests/test_public_conversation_stream_protocol.py \
  tests/test_public_conversation_http.py \
  tests/test_public_conversation_protocol.py
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add server/main/xiaozhi-server/core/public_conversation/protocol.py \
  server/main/xiaozhi-server/core/api/public_conversation_handler.py \
  server/main/xiaozhi-server/tests/test_public_conversation_stream_protocol.py
git commit -m "feat: define public streaming conversation protocol"
```

### Task 2: Implement streaming ASR session and endpointing

**Files:**
- Create: `server/main/xiaozhi-server/core/public_conversation/streaming_asr.py`
- Modify: `server/main/xiaozhi-server/core/public_conversation/session.py`
- Create: `server/main/xiaozhi-server/tests/test_public_conversation_streaming_asr.py`

- [ ] **Step 1: Write failing ASR stream tests**

```python
@pytest.mark.asyncio
async def test_audio_frames_produce_partial_and_final_events():
    asr = FakeStreamingAsr(partials=["深圳今天", "深圳今天天气怎么样"])
    stream = PublicStreamingAsr(asr, emit=lambda event: events.append(event))
    await stream.start()
    await stream.push(b"pcm-a")
    await stream.end_utterance()
    assert [event.event_type for event in events] == ["asr.partial", "asr.partial", "asr.final"]
    assert events[-1].details["text"] == "深圳今天天气怎么样"

@pytest.mark.asyncio
async def test_cancel_discards_pending_audio_and_closes_provider():
    stream = PublicStreamingAsr(FakeStreamingAsr(), emit=lambda _event: None)
    await stream.start()
    await stream.push(b"pcm")
    await stream.cancel()
    assert stream.closed is True
```

- [ ] **Step 2: Run the tests and verify RED**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q tests/test_public_conversation_streaming_asr.py
```

Expected: FAIL because `PublicStreamingAsr` does not exist.

- [ ] **Step 3: Implement provider adapter and partial event normalization**

Wrap the existing ASR provider selected by the runtime bundle. Feed PCM frames through an asyncio queue, normalize provider partial/final callbacks to `asr.partial` and `asr.final`, keep only the latest partial text for each utterance, and never emit duplicate final text. If the provider lacks a streaming callback, use a bounded buffer fallback and emit only `asr.final` after `end_utterance`.

- [ ] **Step 4: Add endpointing and cancellation**

Use provider VAD when available; otherwise apply the browser-confirmed 700 ms trailing silence threshold on server input. `end_utterance()` must be idempotent, create exactly one turn handoff, and reset the utterance buffer. `cancel()` must stop provider tasks, discard pending bytes, and leave the connection object reusable for the next stream start.

- [ ] **Step 5: Run ASR and existing public tests**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q \
  tests/test_public_conversation_streaming_asr.py \
  tests/test_public_conversation_*.py
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add server/main/xiaozhi-server/core/public_conversation/streaming_asr.py \
  server/main/xiaozhi-server/core/public_conversation/session.py \
  server/main/xiaozhi-server/tests/test_public_conversation_streaming_asr.py
git commit -m "feat: add streaming ASR session"
```

### Task 3: Stream LLM and TTS output with cancellation

**Files:**
- Create: `server/main/xiaozhi-server/core/public_conversation/streaming_turn.py`
- Modify: `server/main/xiaozhi-server/core/public_conversation/session.py`
- Modify: `server/main/xiaozhi-server/core/public_conversation/tools.py`
- Create: `server/main/xiaozhi-server/tests/test_public_conversation_streaming_turn.py`

- [ ] **Step 1: Write failing turn pipeline tests**

```python
@pytest.mark.asyncio
async def test_first_llm_delta_and_first_tts_chunk_are_emitted_before_completion():
    events = []
    turn = PublicStreamingTurn(llm=FakeStreamingLlm(["深圳当前", " 28 度。"]), tts=FakeChunkedTts([b"a", b"b"]), emit=events.append)
    await turn.run("深圳今天天气怎么样")
    assert [event.event_type for event in events] == [
        "llm.delta", "llm.delta", "tts.audio.chunk", "tts.audio.chunk", "turn.completed",
    ]
    assert events[2].details["chunk_index"] == 0
    assert events[2].details["final"] is False

@pytest.mark.asyncio
async def test_cancel_stops_old_tts_before_new_turn_starts():
    turn = PublicStreamingTurn(llm=SlowLlm(), tts=SlowTts(), emit=lambda _event: None)
    task = asyncio.create_task(turn.run("第一句"))
    await turn.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
```

- [ ] **Step 2: Run tests and verify RED**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q tests/test_public_conversation_streaming_turn.py
```

Expected: FAIL because the streaming turn pipeline does not exist.

- [ ] **Step 3: Implement incremental LLM generation**

Reuse the existing public tool runtime and `response_with_functions`. For a turn with tools, emit `tool.started`, execute the bounded tool, append internal `tool` messages, and continue the same LLM stream. For a turn without tools, call the existing streaming response. Emit `llm.delta` immediately for every non-empty visible token and enforce the existing output limit.

- [ ] **Step 4: Implement chunked TTS adapter**

Add a provider adapter that accepts either an async iterator/callback of PCM/Opus chunks or the existing complete-byte result. For a streaming provider emit each chunk as `tts.audio.chunk` with `chunk_index`, `mime_type`, `final`, and base64 data. For a complete-only provider emit one `final=false` chunk followed by a zero-length `final=true` marker. Enforce per-chunk and total output limits.

- [ ] **Step 5: Add cancellation and stale-turn protection**

Every LLM, tool, and TTS task checks a turn cancellation event. On cancellation, stop emitting chunks, cancel provider tasks, emit exactly one `turn.cancelled`, and prevent a late old chunk from entering the socket. The next turn gets a new `turn_id` and sequence continues monotonically.

- [ ] **Step 6: Run streaming turn and legacy tests**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q \
  tests/test_public_conversation_streaming_turn.py \
  tests/test_public_conversation_tool_calls.py \
  tests/test_public_conversation_http.py \
  tests/test_public_conversation_*.py
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add server/main/xiaozhi-server/core/public_conversation \
  server/main/xiaozhi-server/tests/test_public_conversation_streaming_turn.py
git commit -m "feat: stream public LLM and TTS responses"
```

### Task 4: Add streaming WebSocket session lifecycle

**Files:**
- Create: `server/main/xiaozhi-server/core/public_conversation/streaming_session.py`
- Modify: `server/main/xiaozhi-server/core/api/public_conversation_handler.py`
- Create: `server/main/xiaozhi-server/tests/test_public_conversation_streaming_http.py`

- [ ] **Step 1: Write failing HTTP lifecycle tests**

```python
@pytest.mark.asyncio
async def test_stream_start_accepts_pcm_frames_and_emits_partial_final_llm_and_tts():
    ws = await connect_stream()
    await ws.send_json({"type": "stream.start", "request_id": "r1", "audio": {"format": "pcm_s16le", "sample_rate": 16000, "channels": 1}})
    assert (await ws.receive_json())["type"] == "stream.ready"
    await ws.send_bytes(b"pcm")
    await ws.send_json({"type": "stream.audio.end"})
    event_types = await receive_until_completed(ws)
    assert event_types[:3] == ["asr.partial", "asr.final", "llm.delta"]
    assert "tts.audio.chunk" in event_types

@pytest.mark.asyncio
async def test_stream_cancel_keeps_socket_open_for_next_utterance():
    ws = await connect_stream()
    await ws.send_json(stream_start())
    await ws.receive_json()
    await ws.send_json({"type": "turn.cancel", "turn_id": "turn-a"})
    assert (await ws.receive_json())["type"] in {"turn.cancelled", "error"}
    await ws.send_json({"type": "stream.stop"})
    assert (await ws.receive()).type == WSMsgType.CLOSE
```

- [ ] **Step 2: Run tests and verify RED**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q tests/test_public_conversation_streaming_http.py
```

Expected: FAIL because the handler has no streaming lifecycle.

- [ ] **Step 3: Implement `PublicStreamingSession`**

Own stream start validation, ASR task, input queue, current utterance, active turn map, maximum two active turns, local pending queue, and stop/cancel cleanup. `push_audio` never waits on LLM/TTS; it writes to ASR queue. `end_audio` awaits the ASR final boundary and schedules the turn pipeline. `stop` cancels all tasks and closes provider resources.

- [ ] **Step 4: Route stream controls in aiohttp handler**

On `stream.start`, create `PublicStreamingSession`, emit `stream.ready`, and enter a receive loop that accepts JSON controls and binary PCM. Dispatch emitted events through the existing send lock with a 10-second backpressure timeout. Keep the old receive loop untouched when `stream_mode` is false.

- [ ] **Step 5: Enforce connection and resource limits**

Use the existing runtime-token deadline and 15-minute connection cap. Reject frames over the existing 2 MiB limit, audio beyond the per-turn duration, unsupported sample format, more than two active turns, and more than 100 completed turns with stable error events. A failed turn must not close a healthy stream.

- [ ] **Step 6: Run all streaming HTTP and legacy public tests**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q \
  tests/test_public_conversation_stream_protocol.py \
  tests/test_public_conversation_streaming_asr.py \
  tests/test_public_conversation_streaming_turn.py \
  tests/test_public_conversation_streaming_http.py \
  tests/test_public_conversation_*.py
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add server/main/xiaozhi-server/core/public_conversation \
  server/main/xiaozhi-server/tests/test_public_conversation_streaming_*.py
git commit -m "feat: add public streaming websocket session"
```

### Task 5: Switch the realtime browser tab to streaming mode

**Files:**
- Modify: `docs/api/public-conversation-demo.js`
- Modify: `docs/api/public-conversation-demo.html`
- Modify: `docs/api/public-conversation-audio.js`
- Create: `scripts/public-conversation-stream-client.test.mjs`

- [ ] **Step 1: Write failing client protocol tests**

```javascript
test("realtime tab sends stream.start once and forwards PCM frames immediately", async () => {
  const socket = new FakeSocket();
  const client = createPublicStreamingClient({ socketFactory: () => socket });
  await client.start();
  assert.deepEqual(JSON.parse(socket.sent[0]), { type: "stream.start", request_id: "r1", audio: { format: "pcm_s16le", sample_rate: 16000, channels: 1 } });
  client.pushAudio(new Uint8Array([1, 2]));
  assert.deepEqual(socket.binary[0], new Uint8Array([1, 2]));
});

test("new TTS chunks replace stale audio and user speech ducks playback", () => {
  const client = createPublicStreamingClient({ socketFactory: () => new FakeSocket() });
  client.handleEvent({ type: "tts.audio.chunk", turn_id: "t1", details: { chunk_index: 0, final: false, data: "..." } });
  client.handleEvent({ type: "tts.audio.chunk", turn_id: "t2", details: { chunk_index: 0, final: false, data: "..." } });
  assert.equal(client.snapshot().activeAudioTurnId, "t2");
  client.setUserSpeaking(true);
  assert.equal(client.snapshot().playbackVolume, 0.2);
});
```

- [ ] **Step 2: Run tests and verify RED**

```bash
node --test scripts/public-conversation-stream-client.test.mjs
```

Expected: FAIL because the realtime tab still sends legacy `turn.audio.*` frames.

- [ ] **Step 3: Implement the browser streaming client**

Create a focused client adapter with `start`, `pushAudio`, `endAudio`, `cancelTurn`, `stop`, `handleEvent`, and `snapshot`. It sends `stream.start` once per socket, forwards PCM frames as binary, handles `asr.partial`, `asr.final`, `llm.delta`, `tts.audio.chunk`, `turn.cancelled`, and `stream.expired`, and discards stale TTS chunks by turn order.

- [ ] **Step 4: Replace realtime tab capture path**

Use the streaming client only in realtime mode. Keep the ordinary tab on the existing auto-segmented legacy path until the new stream passes acceptance. Render partial ASR text while speaking, final user text when the utterance ends, and start audio playback on the first TTS chunk. Keep the existing 20% ducking behavior and metrics.

- [ ] **Step 5: Add browser cache version and UI state**

Update the module query version, show `实时流式连接`, `ASR partial`, `首个 LLM token`, and `首个 TTS 音频块` metrics, and keep the stop button available while a turn is processing.

- [ ] **Step 6: Run Node tests and syntax checks**

```bash
node --test scripts/public-conversation-stream-client.test.mjs \
  scripts/public-conversation-continuous.test.mjs \
  scripts/public-conversation-vad.test.mjs \
  scripts/public-conversation-audio.test.mjs \
  scripts/public-conversation-realtime.test.mjs
node --check docs/api/public-conversation-demo.js
git diff --check
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add docs/api/public-conversation-demo.js docs/api/public-conversation-demo.html \
  docs/api/public-conversation-audio.js scripts/public-conversation-stream-client.test.mjs
git commit -m "feat: use streaming protocol in realtime tab"
```

### Task 6: Cross-layer and real-browser acceptance

**Files:**
- Modify only if verification finds a defect: files from Tasks 1-5
- Modify: `docs/api/ai-live-runtime-protocols.md`
- Modify: `docs/public-conversation-progress.md`

- [ ] **Step 1: Run Java, Python, and browser unit gates**

```bash
cd server/main/manager-api
JAVA_HOME=/Users/xiaox/WorkShop/ai-plush-companion-public/.codex-tmp/jdk21-download/jdk-21.0.12+8/Contents/Home \
PATH=/Users/xiaox/WorkShop/ai-plush-companion-public/.codex-tmp/jdk21-download/jdk-21.0.12+8/Contents/Home/bin:$PATH \
mvn -q -Dtest='xiaozhi.modules.conversation.**' test

cd ../xiaozhi-server
../../../.venv312/bin/python -m pytest -q tests/test_public_conversation_*.py

cd ../../..
node --test scripts/public-conversation-stream-client.test.mjs \
  scripts/public-conversation-continuous.test.mjs \
  scripts/public-conversation-vad.test.mjs \
  scripts/public-conversation-audio.test.mjs \
  scripts/public-conversation-realtime.test.mjs
```

Expected: all selected tests pass.

- [ ] **Step 2: Verify real streaming text and first-byte TTS**

Create a fresh conversation for the bound Agent version and open the realtime tab. Send `stream.start`, speak one sentence, and capture timestamps for `asr.partial`, `asr.final`, first `llm.delta`, first `tts.audio.chunk`, and `turn.completed`.

Expected: partial ASR precedes final ASR, first LLM delta precedes turn completion, first TTS chunk arrives before the final completion event, and the socket remains open.

- [ ] **Step 3: Verify interruption and stale audio replacement**

Start a long AI reply, speak a second sentence before the first TTS stream ends, and inspect events.

Expected: the first turn is cancelled or marked interrupted, the client lowers old playback, the second `turn_id` starts, old chunks after cancellation are ignored, and the newest TTS chunk becomes audible first.

- [ ] **Step 4: Verify failures and legacy compatibility**

Close the socket during an audio stream and send one legacy `turn.audio.start/end` request through a separate session.

Expected: streaming session emits a stable error/close and cleans resources; legacy request still returns the existing event sequence.

- [ ] **Step 5: Update protocol documentation and final checks**

Document `stream.start`, `stream.audio.end`, `stream.stop`, `asr.partial`, `tts.audio.chunk`, cancellation and limits in `docs/api/ai-live-runtime-protocols.md` and progress notes. Run `git diff --check`, Node syntax checks and the selected test gates again.
