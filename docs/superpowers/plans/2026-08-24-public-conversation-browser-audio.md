# Public Conversation Browser Audio Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the public conversation demo play every `tts.audio` reply in order while preserving streamed text and keeping the WebSocket usable when playback fails.

**Architecture:** Add a focused browser audio queue module that owns base64 decoding, Blob URLs, playback order, retry after autoplay blocking, and cleanup. The existing demo remains responsible for conversation events and delegates only `tts.audio` details to that module.

**Tech Stack:** Browser ES modules, Web Audio media playback through `Audio`, Blob URLs, Node.js built-in test runner, live Java/Python public conversation services.

---

### Task 1: Audio playback queue

**Files:**
- Create: `docs/api/public-conversation-audio.js`
- Create: `scripts/public-conversation-audio.test.mjs`

- [ ] **Step 1: Write the failing queue tests**

Create `scripts/public-conversation-audio.test.mjs` with fake audio objects so the public queue interface can be tested without a browser:

```javascript
import assert from "node:assert/strict";
import test from "node:test";

import { createAudioPlaybackQueue } from "../docs/api/public-conversation-audio.js";

class FakeAudio {
  static instances = [];
  static rejectPlayback = false;

  constructor(url) {
    this.url = url;
    this.listeners = new Map();
    this.pauseCalls = 0;
    this.playCalls = 0;
    FakeAudio.instances.push(this);
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener);
  }

  pause() {
    this.pauseCalls += 1;
  }

  play() {
    this.playCalls += 1;
    if (FakeAudio.rejectPlayback) {
      return Promise.reject(new DOMException("blocked", "NotAllowedError"));
    }
    return Promise.resolve();
  }

  emit(type) {
    this.listeners.get(type)?.();
  }
}

function fixture(overrides = {}) {
  FakeAudio.instances = [];
  FakeAudio.rejectPlayback = false;
  const created = [];
  const revoked = [];
  const blocked = [];
  const errors = [];
  const playing = [];
  const player = createAudioPlaybackQueue({
    AudioCtor: FakeAudio,
    createObjectURL(blob) {
      const url = `blob:test-${created.length + 1}`;
      created.push({ url, blob });
      return url;
    },
    revokeObjectURL(url) {
      revoked.push(url);
    },
    onBlocked() {
      blocked.push(true);
    },
    onError(error) {
      errors.push(error);
    },
    onPlaying() {
      playing.push(true);
    },
    ...overrides,
  });
  return { player, created, revoked, blocked, errors, playing };
}

const wav = Buffer.from("RIFF-test-audio").toString("base64");

test("plays queued TTS audio in arrival order and releases Blob URLs", async () => {
  const { player, created, revoked } = fixture();
  await player.enqueue({ mime_type: "audio/wav", data: wav });
  await player.enqueue({ mime_type: "audio/wav", data: wav });

  assert.equal(FakeAudio.instances.length, 1);
  assert.equal(FakeAudio.instances[0].playCalls, 1);
  FakeAudio.instances[0].emit("ended");
  await Promise.resolve();

  assert.equal(FakeAudio.instances.length, 2);
  assert.equal(FakeAudio.instances[1].playCalls, 1);
  assert.deepEqual(revoked, [created[0].url]);
});

test("retries the current audio after autoplay is blocked", async () => {
  const { player, blocked, playing } = fixture();
  FakeAudio.rejectPlayback = true;
  await player.enqueue({ mime_type: "audio/wav", data: wav });

  assert.equal(blocked.length, 1);
  FakeAudio.rejectPlayback = false;
  await player.resume();

  assert.equal(FakeAudio.instances[0].playCalls, 2);
  assert.equal(playing.length, 1);
});

test("reports malformed audio without poisoning the queue", async () => {
  const { player, errors } = fixture();
  await player.enqueue({ mime_type: "text/plain", data: "not-base64" });
  await player.enqueue({ mime_type: "audio/wav", data: wav });

  assert.equal(errors.length, 1);
  assert.equal(FakeAudio.instances.length, 1);
  assert.equal(FakeAudio.instances[0].playCalls, 1);
});

test("clear stops playback and releases current and queued URLs", async () => {
  const { player, created, revoked } = fixture();
  await player.enqueue({ mime_type: "audio/wav", data: wav });
  await player.enqueue({ mime_type: "audio/wav", data: wav });
  player.clear();

  assert.equal(FakeAudio.instances[0].pauseCalls, 1);
  assert.deepEqual(revoked, created.map((item) => item.url));
});
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
node --test scripts/public-conversation-audio.test.mjs
```

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `docs/api/public-conversation-audio.js`.

- [ ] **Step 3: Implement the focused queue module**

Create `docs/api/public-conversation-audio.js`:

```javascript
function decodeBase64(value) {
  if (typeof value !== "string" || !value || value.length % 4 !== 0
    || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) {
    throw new TypeError("TTS 音频数据无效");
  }
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

export function createAudioPlaybackQueue({
  AudioCtor = Audio,
  createObjectURL = URL.createObjectURL.bind(URL),
  revokeObjectURL = URL.revokeObjectURL.bind(URL),
  onBlocked = () => {},
  onError = () => {},
  onPlaying = () => {},
} = {}) {
  const queue = [];
  let current = null;
  let blocked = false;
  let disposed = false;

  function release(item, pause = false) {
    if (!item) return;
    if (pause) item.audio.pause();
    revokeObjectURL(item.url);
  }

  async function startCurrent() {
    if (!current || disposed) return;
    try {
      await current.audio.play();
      blocked = false;
      onPlaying();
    } catch (error) {
      if (error?.name === "NotAllowedError") {
        blocked = true;
        onBlocked();
        return;
      }
      onError(error);
      finishCurrent();
    }
  }

  function finishCurrent() {
    if (!current) return;
    release(current);
    current = null;
    blocked = false;
    void playNext();
  }

  async function playNext() {
    if (disposed || current || queue.length === 0) return;
    const item = queue.shift();
    const audio = new AudioCtor(item.url);
    current = { ...item, audio };
    audio.addEventListener("ended", finishCurrent, { once: true });
    audio.addEventListener("error", () => {
      onError(new Error("语音播放失败"));
      finishCurrent();
    }, { once: true });
    await startCurrent();
  }

  return {
    async enqueue(details) {
      if (disposed) return;
      try {
        if (!details?.mime_type?.startsWith("audio/")) {
          throw new TypeError("TTS 音频格式无效");
        }
        const bytes = decodeBase64(details.data);
        const url = createObjectURL(new Blob([bytes], { type: details.mime_type }));
        queue.push({ url });
        await playNext();
      } catch (error) {
        onError(error);
      }
    },
    async resume() {
      if (disposed) return;
      if (current && blocked) await startCurrent();
      else await playNext();
    },
    clear() {
      release(current, true);
      current = null;
      blocked = false;
      while (queue.length) release(queue.shift());
    },
    dispose() {
      this.clear();
      disposed = true;
    },
  };
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run:

```bash
node --test scripts/public-conversation-audio.test.mjs
```

Expected: `4` tests pass and `0` fail.

- [ ] **Step 5: Commit the queue and tests**

```bash
git add docs/api/public-conversation-audio.js scripts/public-conversation-audio.test.mjs
git commit -m "feat: add browser TTS playback queue"
```

### Task 2: Connect TTS events to browser playback

**Files:**
- Modify: `docs/api/public-conversation-demo.js:1-120`
- Modify: `docs/api/public-conversation-demo.html:62-64`

- [ ] **Step 1: Add the audio module import and playback state**

At the top of `docs/api/public-conversation-demo.js`, add:

```javascript
import { createAudioPlaybackQueue } from "./public-conversation-audio.js";
```

After the recording state declarations, add:

```javascript
let audioPlaybackBlocked = false;
let audioPlaybackFailed = false;

function connectedStatus() {
  if (audioPlaybackBlocked) return "点击页面开启声音";
  if (audioPlaybackFailed) return "语音播放失败，文字回复可用";
  return "已连接";
}

const audioPlayback = createAudioPlaybackQueue({
  onBlocked() {
    audioPlaybackBlocked = true;
    setStatus("online", connectedStatus());
  },
  onError() {
    audioPlaybackFailed = true;
    setStatus("online", connectedStatus());
  },
  onPlaying() {
    audioPlaybackBlocked = false;
    audioPlaybackFailed = false;
    setStatus("online", connectedStatus());
  },
});
```

- [ ] **Step 2: Route `tts.audio` and preserve playback status**

In `handleEvent`, add this branch before `turn.completed`:

```javascript
  } else if (event.type === "tts.audio") {
    void audioPlayback.enqueue(event.details);
```

Replace the `turn.completed` status update with:

```javascript
    setStatus("online", connectedStatus());
```

At the start of `connect`, before creating the HTTP session, reset the old player state:

```javascript
  audioPlayback.clear();
  audioPlaybackBlocked = false;
  audioPlaybackFailed = false;
```

In the WebSocket `close` handler and the `session.expired` branch, call:

```javascript
    audioPlayback.clear();
```

- [ ] **Step 3: Resume blocked playback from a user gesture and clean up on unload**

Append these listeners near the existing page listeners:

```javascript
document.addEventListener("pointerdown", () => {
  void audioPlayback.resume();
}, { passive: true });

window.addEventListener("beforeunload", () => {
  audioPlayback.dispose();
});
```

- [ ] **Step 4: Bust the demo module cache**

Change the final module script in `docs/api/public-conversation-demo.html` to:

```html
<script type="module" src="./public-conversation-demo.js?v=20260824-2"></script>
```

- [ ] **Step 5: Run focused static and unit verification**

Run:

```bash
node --check docs/api/public-conversation-audio.js
node --check docs/api/public-conversation-demo.js
node --test scripts/public-conversation-audio.test.mjs
git diff --check
```

Expected: both syntax checks exit `0`, all `4` tests pass, and the whitespace check has no output.

- [ ] **Step 6: Commit the page integration**

```bash
git add docs/api/public-conversation-demo.html docs/api/public-conversation-demo.js
git commit -m "feat: play public conversation voice replies"
```

### Task 3: Live browser acceptance

**Files:**
- Modify only if verification finds a defect: `docs/api/public-conversation-audio.js`
- Modify only if verification finds a defect: `docs/api/public-conversation-demo.js`

- [ ] **Step 1: Confirm all local services are available**

Run:

```bash
curl -fsS http://127.0.0.1:8001/ >/dev/null
curl -fsS http://127.0.0.1:8002/xiaozhi/user/pub-config >/dev/null
curl -fsS http://127.0.0.1:8003/ >/dev/null || test "$?" = "22"
curl -fsS http://127.0.0.1:8010/public-conversation-demo.html >/dev/null
```

Expected: each service accepts a TCP/HTTP request; the Python root may return HTTP `404` while remaining healthy.

- [ ] **Step 2: Open the cache-busted demo and connect the role**

Open `http://127.0.0.1:8010/public-conversation-demo.html?v=20260824-2`, click `连接角色`, and wait for `已连接 · 版本 20`.

Expected: the text input, record button, and send button become enabled with no browser console errors.

- [ ] **Step 3: Send a short text turn and verify real TTS playback**

Send `请只回复语音播放成功` and observe the page until the turn completes.

Expected: the user text and streamed assistant text appear, the WebSocket receives a `tts.audio` event with `mime_type: audio/wav`, and the browser starts media playback. If autoplay is blocked, the status changes to `点击页面开启声音`; one page click resumes the same queued audio.

- [ ] **Step 4: Verify cleanup and reconnection**

Reconnect the role after one completed spoken reply.

Expected: old audio stops, no old reply resumes, a new text turn produces one new spoken reply, and the page remains connected.

- [ ] **Step 5: Run final verification**

Run:

```bash
node --test scripts/public-conversation-audio.test.mjs
node --check docs/api/public-conversation-audio.js
node --check docs/api/public-conversation-demo.js
git diff --check
```

Expected: `4` tests pass, both syntax checks exit `0`, and the whitespace check has no output.
