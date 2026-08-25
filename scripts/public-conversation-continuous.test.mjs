import assert from "node:assert/strict";
import test from "node:test";

import { createPlaybackDucker } from "../docs/api/public-conversation-audio.js";
import { createAudioSendQueue } from "../docs/api/public-conversation-send-queue.js";
import { createVoiceSegmenter } from "../docs/api/public-conversation-vad.js";

test("continuous mode segments speech, queues sends, and keeps the socket open", async () => {
  const socket = { readyState: 1, sent: [], send(payload) { this.sent.push(payload); } };
  const segments = [];
  const segmenter = createVoiceSegmenter({ minSpeechMs: 40, endSilenceMs: 30, onSegment: (item) => segments.push(item) });
  const ducker = createPlaybackDucker({ duckVolume: 0.2 });
  const audio = { volume: 1, pause() {} };
  ducker.register(audio);
  for (let index = 0; index < 6; index += 1) segmenter.push(new Float32Array(160).fill(0.1));
  for (let index = 0; index < 4; index += 1) segmenter.push(new Float32Array(160));
  assert.equal(segments.length, 1);

  const queue = createAudioSendQueue({
    maxActive: 2,
    send: async (segment) => socket.send(segment.pcm),
  });
  ducker.setUserSpeaking(true);
  await queue.enqueue(segments[0]);
  ducker.setUserSpeaking(false);

  assert.equal(socket.readyState, 1);
  assert.equal(socket.sent.length, 1);
  assert.equal(audio.volume, 1);
});
