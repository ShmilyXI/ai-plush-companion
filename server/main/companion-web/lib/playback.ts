/**
 * 播放角色的语音回复。
 *
 * 手机浏览器（iOS Safari、微信内置浏览器）不允许页面自己开始播放声音：回复是在用户点了发送
 * 之后几秒才到的，那时再新建 <audio> 去播放会被拦截。这里用一个共享的 AudioContext，
 * 在用户第一次触摸或按键时解锁，之后到达的语音都通过它播放。
 * 没有解锁成功或浏览器不支持时，退回到逐个 <audio> 元素播放（桌面浏览器通常允许）。
 */
export interface Playback {
  /** 必须在用户手势（点击、触摸、按键）的事件处理里调用。可以重复调用。 */
  unlock(): void
  play(bytes: Uint8Array, mimeType: string): void
  /** 立刻停止，并丢弃还没开始播放的片段。 */
  stop(): void
  isPlaying(): boolean
  /** 当前这段回复已经播放了多少毫秒，用于告诉服务端用户在哪里打断的。 */
  playedMs(): number
  /** 现在正在播放的声音有多响，0 到 1。用来让角色跟着自己的声音动。 */
  level(): number
  dispose(): void
}

type ContextCtor = new () => AudioContext

function contextCtor(): ContextCtor | null {
  if (typeof window === 'undefined') return null
  const scope = window as unknown as { AudioContext?: ContextCtor; webkitAudioContext?: ContextCtor }
  return scope.AudioContext ?? scope.webkitAudioContext ?? null
}

export function createPlayback(): Playback {
  let context: AudioContext | null = null
  let sources = new Set<AudioBufferSourceNode>()
  let nextStartAt = 0
  let firstStartedAt: number | null = null
  // 解码是异步的，用一条链保证片段按到达顺序排队；epoch 用来作废 stop() 之前排进去的片段。
  let chain: Promise<void> = Promise.resolve()
  let epoch = 0
  let elements: HTMLAudioElement[] = []
  let elementQueue: HTMLAudioElement[] = []
  let elementActive = false
  // 所有片段都经过这个分析节点再到扬声器，用来读出当前的音量。
  let analyser: AnalyserNode | null = null
  let samples: Uint8Array<ArrayBuffer> | null = null

  function unlock() {
    const Ctor = contextCtor()
    if (!Ctor) return
    try {
      if (!context) context = new Ctor()
      if (context.state === 'suspended') void context.resume().catch(() => undefined)
    } catch { context = null }
  }

  function schedule(buffer: AudioBuffer, scheduledEpoch: number) {
    if (!context || scheduledEpoch !== epoch) return
    const source = context.createBufferSource()
    source.buffer = buffer
    if (!analyser && typeof context.createAnalyser === 'function') {
      try {
        analyser = context.createAnalyser()
        analyser.fftSize = 512
        analyser.connect(context.destination)
        samples = new Uint8Array(new ArrayBuffer(analyser.fftSize))
      } catch { analyser = null }
    }
    source.connect(analyser ?? context.destination)
    const startAt = Math.max(context.currentTime, nextStartAt)
    if (firstStartedAt === null || sources.size === 0) firstStartedAt = startAt
    nextStartAt = startAt + buffer.duration
    sources.add(source)
    source.onended = () => { sources.delete(source) }
    source.start(startAt)
  }

  function playWithElement(bytes: Uint8Array, mimeType: string) {
    const url = URL.createObjectURL(new Blob([bytes.slice().buffer as ArrayBuffer], { type: mimeType }))
    const audio = new Audio(url)
    elements.push(audio)
    elementQueue.push(audio)
    const playNext = () => {
      if (elementActive) return
      const next = elementQueue.shift()
      if (!next) return
      elementActive = true
      const finish = () => {
        elements = elements.filter((item) => item !== next)
        URL.revokeObjectURL(next.src)
        elementActive = false
        playNext()
      }
      next.onended = finish
      void next.play().catch(finish)
    }
    playNext()
  }

  function play(bytes: Uint8Array, mimeType: string) {
    if (!bytes.length) return
    if (!context || context.state !== 'running') { playWithElement(bytes, mimeType); return }
    const activeContext = context
    const scheduledEpoch = epoch
    const copy = bytes.slice().buffer as ArrayBuffer
    chain = chain
      .then(() => activeContext.decodeAudioData(copy))
      .then((buffer) => schedule(buffer, scheduledEpoch))
      // 解码失败（格式不支持）时退回到 <audio> 元素，由浏览器自己处理。
      .catch(() => { if (scheduledEpoch === epoch) playWithElement(bytes, mimeType) })
  }

  function stop() {
    epoch += 1
    for (const source of sources) { try { source.stop() } catch { /* 还没开始或已经结束 */ } }
    sources = new Set()
    nextStartAt = 0
    firstStartedAt = null
    for (const audio of elements) { audio.pause(); URL.revokeObjectURL(audio.src) }
    elements = []; elementQueue = []; elementActive = false
  }

  function isPlaying() {
    return sources.size > 0 || elements.length > 0
  }

  function playedMs() {
    if (context && firstStartedAt !== null && sources.size > 0) {
      return Math.max(0, Math.round((context.currentTime - firstStartedAt) * 1000))
    }
    return Math.round((elements[0]?.currentTime || 0) * 1000)
  }

  function level() {
    if (analyser && samples && sources.size > 0) {
      analyser.getByteTimeDomainData(samples)
      let sum = 0
      for (const value of samples) { const centered = (value - 128) / 128; sum += centered * centered }
      // 说话声的均方根通常在 0.05 到 0.3 之间，放大到 0 到 1。
      return Math.min(1, Math.sqrt(sum / samples.length) * 4)
    }
    // 退回到 <audio> 元素播放时读不到音量：给一个缓慢起伏的值，让角色看起来仍然在说话。
    if (elements.length > 0) return 0.35 + 0.25 * Math.sin(Date.now() / 140)
    return 0
  }

  function dispose() {
    stop()
    analyser = null; samples = null
    if (context) void context.close().catch(() => undefined)
    context = null
  }

  return { unlock, play, stop, isPlaying, playedMs, level, dispose }
}
