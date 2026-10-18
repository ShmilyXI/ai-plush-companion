# Realtime Continuous Voice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the realtime tab listening after one click, automatically segment and send speech, and duck AI playback while the user is speaking.

**Architecture:** Extract browser-only VAD segmentation and bounded audio send queue into a small module. The existing public WebSocket turn protocol remains unchanged: each detected utterance becomes one `turn.audio.start` + binary PCM + `turn.audio.end` request, with at most two server turns active and additional segments queued locally. The existing audio playback queue gains a ducking controller that changes volume without stopping playback.

**Tech Stack:** Browser ES modules, Web Audio `AudioContext` and `ScriptProcessorNode`, energy-based VAD, existing public WebSocket protocol, Node.js built-in tests, Playwright live browser checks.

---

### Task 1: Implement deterministic VAD segmentation

**Files:**
- Create: `docs/api/public-conversation-vad.js`
- Create: `scripts/public-conversation-vad.test.mjs`

- [ ] **Step 1: Write the failing segmentation tests**

```javascript
import assert from "node:assert/strict";
import test from "node:test";
import { createVoiceSegmenter } from "../docs/api/public-conversation-vad.js";

const frame = (value, length = 160) => new Float32Array(length).fill(value);

test("starts speech above threshold and ends after configured silence", () => {
  const segments = [];
  const segmenter = createVoiceSegmenter({
    sampleRate: 16000,
    frameMs: 10,
    startThreshold: 0.04,
    endThreshold: 0.015,
    endSilenceMs: 300,
    minSpeechMs: 80,
    onSegment: (segment) => segments.push(segment),
  });

  for (let index = 0; index < 10; index += 1) segmenter.push(frame(0.1));
  for (let index = 0; index < 31; index += 1) segmenter.push(frame(0));

  assert.equal(segments.length, 1);
  assert.equal(segments[0].durationMs, 400);
  assert.equal(segments[0].pcm.length, 16000 * 2 * 0.4);
});

test("keeps short pauses inside one utterance", () => {
  const segments = [];
  const segmenter = createVoiceSegmenter({ endSilenceMs: 300, minSpeechMs: 80, onSegment: (item) => segments.push(item) });
  for (let index = 0; index < 12; index += 1) segmenter.push(frame(0.1));
  for (let index = 0; index < 12; index += 1) segmenter.push(frame(0));
  for (let index = 0; index < 12; index += 1) segmenter.push(frame(0.1));
  for (let index = 0; index < 31; index += 1) segmenter.push(frame(0));

  assert.equal(segments.length, 1);
  assert.equal(segments[0].durationMs, 660);
});

test("flushes speech on stop and discards too-short noise", () => {
  const segments = [];
  const segmenter = createVoiceSegmenter({ minSpeechMs: 80, onSegment: (item) => segments.push(item) });
  for (let index = 0; index < 3; index += 1) segmenter.push(frame(0.1));
  segmenter.flush();
  assert.equal(segments.length, 0);

  for (let index = 0; index < 12; index += 1) segmenter.push(frame(0.1));
  segmenter.flush();
  assert.equal(segments.length, 1);
});
```

- [ ] **Step 2: Run the test and verify RED**

```bash
node --test scripts/public-conversation-vad.test.mjs
```

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `docs/api/public-conversation-vad.js`.

- [ ] **Step 3: Implement the segmenter**

Implement `createVoiceSegmenter({ sampleRate = 16000, frameMs = 10, startThreshold = 0.04, endThreshold = 0.015, endSilenceMs = 300, minSpeechMs = 80, onSegment })`. For every PCM frame calculate RMS energy, start buffering when energy reaches `startThreshold`, append all subsequent frames including silence, and emit once accumulated trailing silence reaches `endSilenceMs`. Convert samples to little-endian signed 16-bit bytes in the emitted `{ pcm, durationMs }`. `flush()` emits only a segment meeting `minSpeechMs`; `reset()` clears all buffered state without emitting.

- [ ] **Step 4: Run the tests and verify GREEN**

```bash
node --test scripts/public-conversation-vad.test.mjs
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add docs/api/public-conversation-vad.js scripts/public-conversation-vad.test.mjs
git commit -m "feat: add browser voice segmenter"
```

### Task 2: Add bounded audio send queue and playback ducking

**Files:**
- Create: `docs/api/public-conversation-send-queue.js`
- Modify: `docs/api/public-conversation-audio.js`
- Create: `scripts/public-conversation-send-queue.test.mjs`
- Modify: `scripts/public-conversation-audio.test.mjs`

- [ ] **Step 1: Write failing queue and ducking tests**

```javascript
import assert from "node:assert/strict";
import test from "node:test";
import { createAudioSendQueue } from "../docs/api/public-conversation-send-queue.js";
import { createPlaybackDucker } from "../docs/api/public-conversation-audio.js";

test("keeps only the configured number of active sends and drains in order", async () => {
  const sent = [];
  const queue = createAudioSendQueue({ maxActive: 2, send: async (item) => { sent.push(item.id); } });
  await Promise.all([queue.enqueue({ id: "a" }), queue.enqueue({ id: "b" }), queue.enqueue({ id: "c" })]);
  assert.deepEqual(sent, ["a", "b", "c"]);
  assert.equal(queue.snapshot().queued, 0);
  assert.equal(queue.snapshot().active, 0);
});

test("ducks and restores every active playback element without pausing it", () => {
  const first = { volume: 1, pauseCalls: 0, pause() { this.pauseCalls += 1; } };
  const second = { volume: 0.6, pauseCalls: 0, pause() { this.pauseCalls += 1; } };
  const ducker = createPlaybackDucker({ duckVolume: 0.2 });
  ducker.register(first);
  ducker.register(second);
  ducker.setUserSpeaking(true);
  assert.equal(first.volume, 0.2);
  assert.equal(second.volume, 0.2);
  assert.equal(first.pauseCalls, 0);
  ducker.setUserSpeaking(false);
  assert.equal(first.volume, 1);
  assert.equal(second.volume, 0.6);
});
```

- [ ] **Step 2: Run tests and verify RED**

```bash
node --test scripts/public-conversation-send-queue.test.mjs scripts/public-conversation-audio.test.mjs
```

Expected: FAIL because the send queue and `createPlaybackDucker` exports do not exist.

- [ ] **Step 3: Implement the queue and ducker**

The queue exposes `enqueue(item)`, `clear()`, `snapshot()`, and `setError(handler)`. `maxActive` is 2; each `send(item)` promise is awaited before the slot is released. `clear()` rejects queued items with a stable `cleared` error and does not cancel an in-flight WebSocket frame. The ducker exposes `register(audio)`, `unregister(audio)`, `setUserSpeaking(boolean)`, and `clear()`, stores each element's original volume, clamps duck volume to `[0, 1]`, and never calls `pause()`.

- [ ] **Step 4: Run tests and verify GREEN**

```bash
node --test scripts/public-conversation-send-queue.test.mjs scripts/public-conversation-audio.test.mjs
```

Expected: all queue/audio tests pass.

- [ ] **Step 5: Commit**

```bash
git add docs/api/public-conversation-send-queue.js docs/api/public-conversation-audio.js \
  scripts/public-conversation-send-queue.test.mjs scripts/public-conversation-audio.test.mjs
git commit -m "feat: add continuous voice send queue and playback ducking"
```

### Task 3: Convert realtime tab to continuous call mode

**Files:**
- Modify: `docs/api/public-conversation-demo.js:45-660`
- Modify: `docs/api/public-conversation-demo.html:80-100`
- Modify: `docs/api/public-conversation-demo.css`
- Create: `scripts/public-conversation-continuous.test.mjs`

- [ ] **Step 1: Write failing continuous-mode state tests**

Test the public controller seams with fake segmenter, queue, socket and audio elements:

```javascript
test("starting realtime mode starts the microphone loop and keeps controls enabled", async () => {
  const controller = createRealtimeCallController(fixture());
  await controller.start();
  assert.equal(controller.snapshot().state, "listening");
  assert.equal(controller.snapshot().micActive, true);
  assert.equal(controller.snapshot().queuedSegments, 0);
});

test("a completed segment is sent immediately while the socket remains open", async () => {
  const fixtureState = fixture();
  const controller = createRealtimeCallController(fixtureState);
  await controller.start();
  fixtureState.emitSegment({ id: "segment-a", pcm: new Uint8Array([1, 2]), durationMs: 100 });
  await fixtureState.flush();
  assert.deepEqual(fixtureState.sent, ["segment-a"]);
  assert.equal(fixtureState.socket.readyState, WebSocket.OPEN);
});

test("user speech ducks AI playback and silence restores it", async () => {
  const fixtureState = fixture();
  const controller = createRealtimeCallController(fixtureState);
  await controller.start();
  fixtureState.emitSpeech(true);
  assert.equal(fixtureState.audio.volume, 0.2);
  fixtureState.emitSpeech(false);
  assert.equal(fixtureState.audio.volume, 1);
});
```

- [ ] **Step 2: Run the test and verify RED**

```bash
node --test scripts/public-conversation-continuous.test.mjs
```

Expected: FAIL because the demo has no reusable continuous-call controller.

- [ ] **Step 3: Extract the controller from the page event handlers**

Create `createRealtimeCallController` inside `docs/api/public-conversation-demo.js` or a focused module if the extracted seam is cleaner. It owns `start()`, `stop()`, `onSegment()`, `onSpeechState()`, `snapshot()`, and `dispose()`. `start()` obtains the microphone once, creates the segmenter and ScriptProcessor pipeline, registers current AI audio elements with the ducker, and changes the realtime mode state to `listening`. It does not call `getUserMedia` again for every segment.

- [ ] **Step 4: Send detected segments without closing the socket**

When the segmenter emits, create a request ID, append a realtime user turn with the WAV preview, and enqueue the segment. The queue sender must send the existing three frames and leave `conversation` open. On `tool`, ASR, LLM, TTS, and completion events, update the matching turn and diagnostics as today. Use `snapshot()` to render `麦克风`, `待发送`, `活跃轮次`, and `播放音量` metrics.

- [ ] **Step 5: Implement VAD-triggered ducking**

Call `ducker.setUserSpeaking(true)` immediately when the segmenter enters speech and `false` after the segmenter's end-silence callback. The AI playback queue must register each created audio element, unregister/revoke it on end, and preserve the volume selected before ducking.

- [ ] **Step 6: Keep the realtime mode active through AI replies**

Do not call `stopRecording`, close the socket, or disable realtime controls on `tts.audio` or `turn.completed`. Only the explicit `退出实时通话`, microphone failure, socket close, session expiry, or fatal audio-context error stops the loop. If two server turns are active, keep later segments in the local queue and show the queued count.

- [ ] **Step 7: Run state tests and static checks**

```bash
node --test scripts/public-conversation-continuous.test.mjs \
  scripts/public-conversation-vad.test.mjs \
  scripts/public-conversation-send-queue.test.mjs \
  scripts/public-conversation-audio.test.mjs \
  scripts/public-conversation-turn-model.test.mjs \
  scripts/public-conversation-realtime.test.mjs
node --check docs/api/public-conversation-demo.js
git diff --check
```

Expected: all tests pass with no syntax or whitespace errors.

- [ ] **Step 8: Commit**

```bash
git add docs/api/public-conversation-demo.html docs/api/public-conversation-demo.css \
  docs/api/public-conversation-demo.js scripts/public-conversation-continuous.test.mjs
git commit -m "feat: keep realtime call listening continuously"
```

### Task 4: Live continuous voice acceptance

**Files:**
- Modify only if a verification defect is found: files from Tasks 1-3

- [ ] **Step 1: Confirm services and latest demo asset**

```bash
curl -fsS http://127.0.0.1:8002/xiaozhi/user/pub-config >/dev/null
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8003/
curl -fsS http://127.0.0.1:8010/public-conversation-demo.html >/dev/null
```

Expected: Java and static services return successfully; Python may return HTTP 404 at `/` while accepting the connection.

- [ ] **Step 2: Start continuous realtime mode in a real browser**

Connect the current Agent, open `实时链路测试`, click `开始实时通话`, and grant the microphone permission if the browser asks.

Expected: status remains `实时通话已开启`, microphone metric stays active, and the WebSocket connection remains `已连接`.

- [ ] **Step 3: Verify automatic segmentation and first turn**

Speak one short sentence and stop. Do not press a send button.

Expected: within the silence threshold, one user audio segment is created, `turn.audio.start` is sent automatically, and the event sequence contains `turn.started`, `asr.final`, `llm.delta`, `tts.audio`, `turn.completed`.

- [ ] **Step 4: Verify continuous speaking during AI response**

While the first AI audio is playing, speak a second sentence. Observe the AI audio volume and event log.

Expected: AI audio volume falls to `0.2`, the WebSocket remains open, the second segment enters the queue immediately, and a second `turn_id` starts without leaving realtime mode.

- [ ] **Step 5: Verify queue limit and recovery**

Speak three short segments quickly. Confirm at most two server turns are active, later segments show in the local queue, and the queue drains after earlier turns complete. Exit realtime mode.

Expected: microphone stops, pending segments clear, current playback stops, completed content remains visible, and the normal conversation tab still works.

- [ ] **Step 6: Run final gates**

```bash
node --test scripts/public-conversation-vad.test.mjs \
  scripts/public-conversation-send-queue.test.mjs \
  scripts/public-conversation-continuous.test.mjs \
  scripts/public-conversation-audio.test.mjs \
  scripts/public-conversation-turn-model.test.mjs \
  scripts/public-conversation-realtime.test.mjs
node --check docs/api/public-conversation-demo.js
git diff --check
```

Expected: all tests pass and the continuous browser acceptance has no console errors.
