import assert from "node:assert/strict";
import test from "node:test";

import { createVoiceSegmenter, encodePcm16 } from "../docs/api/public-conversation-vad.js";

test("encodes realtime frames as little-endian signed 16-bit PCM", () => {
  const pcm = encodePcm16(new Float32Array([-1, -0.5, 0, 0.5, 1]));
  assert.deepEqual([...pcm], [1, 128, 1, 192, 0, 0, 255, 63, 255, 127]);
});

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
  assert.equal(Number.isInteger(segments[0].durationMs), true);
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

test("supports a longer end-silence window for natural speaking pauses", () => {
  const segments = [];
  const segmenter = createVoiceSegmenter({ endSilenceMs: 700, minSpeechMs: 40, onSegment: (item) => segments.push(item) });
  for (let index = 0; index < 8; index += 1) segmenter.push(frame(0.1));
  for (let index = 0; index < 60; index += 1) segmenter.push(frame(0));
  assert.equal(segments.length, 0);
  for (let index = 0; index < 11; index += 1) segmenter.push(frame(0));
  assert.equal(segments.length, 1);
});
