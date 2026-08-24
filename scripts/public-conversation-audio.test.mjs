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
