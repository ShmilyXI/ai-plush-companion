export function createVoiceSegmenter({
  sampleRate = 16000,
  frameMs = 10,
  startThreshold = 0.04,
  endThreshold = 0.015,
  endSilenceMs = 300,
  minSpeechMs = 80,
  onSpeechState = () => {},
  onSegment = () => {},
} = {}) {
  const frameSamples = Math.max(1, Math.round(sampleRate * frameMs / 1000));
  let active = false;
  let frames = [];
  let silenceMs = 0;
  let durationMs = 0;

  function emit() {
    if (!frames.length || durationMs < minSpeechMs) return;
    const samples = frames.reduce((total, item) => total + item.length, 0);
    const pcm = new Uint8Array(samples * 2);
    const view = new DataView(pcm.buffer);
    let offset = 0;
    for (const frame of frames) {
      for (const value of frame) {
        const clamped = Math.max(-1, Math.min(1, value));
        view.setInt16(offset, clamped * 0x7fff, true);
        offset += 2;
      }
    }
    onSegment({ pcm, durationMs });
  }

  function reset() {
    active = false;
    frames = [];
    silenceMs = 0;
    durationMs = 0;
    onSpeechState(false);
  }

  return {
    push(input) {
      const frame = input instanceof Float32Array ? input : new Float32Array(input);
      if (!frame.length) return;
      let sum = 0;
      for (const value of frame) sum += value * value;
      const rms = Math.sqrt(sum / frame.length);
      const frameDuration = frame.length / sampleRate * 1000 || frameMs;
      if (!active) {
        if (rms < startThreshold) return;
        active = true;
        onSpeechState(true);
      }
      frames.push(frame.slice());
      durationMs += frameDuration;
      if (rms <= endThreshold) silenceMs += frameDuration;
      else silenceMs = 0;
      if (silenceMs >= endSilenceMs) {
        emit();
        reset();
      }
    },
    flush() {
      emit();
      reset();
    },
    reset,
    snapshot() {
      return { active, durationMs, silenceMs, frameSamples };
    },
  };
}
