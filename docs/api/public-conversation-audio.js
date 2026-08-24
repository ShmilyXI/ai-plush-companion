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

export function createAudioObjectUrl(details, {
  createObjectURL = URL.createObjectURL.bind(URL),
} = {}) {
  if (!details?.mime_type?.startsWith("audio/")) {
    throw new TypeError("TTS 音频格式无效");
  }
  const bytes = decodeBase64(details.data);
  return createObjectURL(new Blob([bytes], { type: details.mime_type }));
}

export function createAudioPlaybackQueue({
  AudioCtor = Audio,
  createObjectURL = URL.createObjectURL.bind(URL),
  revokeObjectURL = URL.revokeObjectURL.bind(URL),
  onBlocked = () => {},
  onError = () => {},
  onIdle = () => {},
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
    if (queue.length === 0) onIdle();
    else void playNext();
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

  function clear() {
    release(current, true);
    current = null;
    blocked = false;
    while (queue.length) release(queue.shift());
  }

  return {
    async enqueue(details) {
      if (disposed) return;
      try {
        const url = createAudioObjectUrl(details, { createObjectURL });
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
    clear,
    dispose() {
      clear();
      disposed = true;
    },
  };
}
