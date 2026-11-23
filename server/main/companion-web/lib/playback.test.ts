import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPlayback } from './playback'

class FakeSource {
  buffer: { duration: number } | null = null
  onended: (() => void) | null = null
  startedAt: number | null = null
  stopped = false
  connect() { return undefined }
  start(when: number) { this.startedAt = when }
  stop() { this.stopped = true }
}

class FakeContext {
  static instances: FakeContext[] = []
  state: 'suspended' | 'running' | 'closed' = 'suspended'
  currentTime = 0
  destination = {}
  sources: FakeSource[] = []
  constructor() { FakeContext.instances.push(this) }
  async resume() { this.state = 'running' }
  async close() { this.state = 'closed' }
  createBufferSource() { const source = new FakeSource(); this.sources.push(source); return source }
  // 用第一个字节当作这段音频的秒数，方便验证排队。
  async decodeAudioData(data: ArrayBuffer) {
    const seconds = new Uint8Array(data)[0]
    if (seconds === 255) throw new Error('unsupported format')
    return { duration: seconds }
  }
}

class FakeAudio {
  static instances: FakeAudio[] = []
  onended: (() => void) | null = null
  currentTime = 0
  paused = false
  played = false
  constructor(readonly src: string) { FakeAudio.instances.push(this) }
  async play() { this.played = true }
  pause() { this.paused = true }
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('playback', () => {
  beforeEach(() => {
    FakeContext.instances = []
    FakeAudio.instances = []
    vi.stubGlobal('Audio', FakeAudio)
    vi.stubGlobal('URL', { createObjectURL: () => 'blob:test', revokeObjectURL: () => undefined })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('plays through the shared context once a gesture has unlocked it, one clip after another', async () => {
    vi.stubGlobal('AudioContext', FakeContext)
    const playback = createPlayback()
    playback.unlock()
    await settle()
    const context = FakeContext.instances[0]
    expect(context.state).toBe('running')

    playback.play(new Uint8Array([2]), 'audio/wav')
    playback.play(new Uint8Array([3]), 'audio/wav')
    await settle()

    expect(context.sources.map((source) => source.startedAt)).toEqual([0, 2])
    expect(FakeAudio.instances).toHaveLength(0)
    expect(playback.isPlaying()).toBe(true)
    context.currentTime = 1.25
    expect(playback.playedMs()).toBe(1250)
  })

  it('knows which sentence is being read right now, for the call caption', async () => {
    vi.stubGlobal('AudioContext', FakeContext)
    const playback = createPlayback()
    playback.unlock()
    await settle()
    const context = FakeContext.instances[0]
    playback.play(new Uint8Array([2]), 'audio/wav', '第一句。')
    playback.play(new Uint8Array([3]), 'audio/wav', '第二句。')
    await settle()

    expect(playback.current()).toBe('第一句。')
    context.currentTime = 2.5
    expect(playback.current()).toBe('第二句。')
    // 第一段播完了也一样；全部停掉后没有字幕。
    context.sources[0].onended?.()
    expect(playback.current()).toBe('第二句。')
    playback.stop()
    expect(playback.current()).toBe('')

    // 退回到 <audio> 元素时，正在播的那个元素的句子就是现在这句。
    vi.stubGlobal('AudioContext', undefined)
    const fallback = createPlayback()
    fallback.play(new Uint8Array([2]), 'audio/mpeg', '甲。')
    fallback.play(new Uint8Array([2]), 'audio/mpeg', '乙。')
    await settle()
    expect(fallback.current()).toBe('甲。')
    FakeAudio.instances[FakeAudio.instances.length - 2].onended?.()
    await settle()
    expect(fallback.current()).toBe('乙。')
  })

  it('falls back to audio elements before any gesture, or where there is no audio context', async () => {
    vi.stubGlobal('AudioContext', FakeContext)
    const locked = createPlayback()
    locked.play(new Uint8Array([2]), 'audio/mpeg')
    await settle()
    expect(FakeContext.instances).toHaveLength(0)
    expect(FakeAudio.instances).toHaveLength(1)
    expect(FakeAudio.instances[0].played).toBe(true)

    vi.stubGlobal('AudioContext', undefined)
    vi.stubGlobal('webkitAudioContext', undefined)
    const unsupported = createPlayback()
    unsupported.unlock()
    unsupported.play(new Uint8Array([2]), 'audio/mpeg')
    await settle()
    expect(FakeAudio.instances).toHaveLength(2)
  })

  it('stops what is playing and drops clips that were still being decoded', async () => {
    vi.stubGlobal('AudioContext', FakeContext)
    const playback = createPlayback()
    playback.unlock()
    await settle()
    const context = FakeContext.instances[0]
    playback.play(new Uint8Array([2]), 'audio/wav')
    await settle()
    playback.play(new Uint8Array([3]), 'audio/wav')

    playback.stop()
    await settle()

    expect(context.sources).toHaveLength(1)
    expect(context.sources[0].stopped).toBe(true)
    expect(playback.isPlaying()).toBe(false)
    expect(playback.playedMs()).toBe(0)

    // 之后的新回复从头开始排，不接在被打断的那段后面。
    playback.play(new Uint8Array([4]), 'audio/wav')
    await settle()
    expect(context.sources[1].startedAt).toBe(context.currentTime)
  })

  it('hands a clip the context cannot decode to an audio element', async () => {
    vi.stubGlobal('AudioContext', FakeContext)
    const playback = createPlayback()
    playback.unlock()
    await settle()

    playback.play(new Uint8Array([255]), 'audio/ogg')
    await settle()

    expect(FakeContext.instances[0].sources).toHaveLength(0)
    expect(FakeAudio.instances).toHaveLength(1)
  })
})
