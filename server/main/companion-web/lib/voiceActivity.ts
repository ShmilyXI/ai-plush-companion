/**
 * 通话时判断“用户是不是在说话”，并决定哪些声音发给服务端。
 *
 * 以前的做法是麦克风收到什么都连续发给服务端，只要有一帧稍微响一点就当成开始说话。
 * 咳嗽、键盘声、关门声都会被当成一句话交给识别，识别出一个逗号，角色就开始回答。
 *
 * 现在的规则：
 * - 平时只在本地留着最近一小段声音（开口前的那一点，免得把第一个字切掉），什么都不发。
 * - 声音明显高于环境噪声，并且持续够久（不是一下爆音），才算开口。这时把留着的那一段和之后的声音发出去。
 * - 说完停顿一会儿就交给服务端识别。
 * - 环境噪声的大小是跟着实际环境慢慢估计的：吵的地方门槛自动抬高。
 * - 角色正在说话时门槛再抬高一些，免得扬声器里漏进麦克风的声音把角色自己打断。
 */

export interface VoiceActivityOptions {
  /** 开口前留多少毫秒的声音。 */
  prerollMs?: number
  /** 有声的部分累计多长才算开口。 */
  minSpeechMs?: number
  /** 疑似开口后这么久还没确认，就当是噪声丢掉。 */
  maxCandidateMs?: number
  /** 疑似开口后安静这么久，就当是噪声丢掉。 */
  candidateSilenceMs?: number
  /** 确认开口后安静这么久，就算这句说完了。 */
  endSilenceMs?: number
  /** 一句话最长多久。再长就先交出去，服务端一段最多收 60 秒。 */
  maxSegmentMs?: number
}

export interface VoiceFrameResult {
  /** 这一帧之后要发给服务端的声音，按顺序。 */
  send: Uint8Array[]
  /** 这一帧确认了用户开口。 */
  started: boolean
  /** 这一帧这句话说完了，要交给服务端识别。值是这句话的时长。 */
  ended: number | null
}

type Phase = 'idle' | 'candidate' | 'speech'

const DEFAULTS: Required<VoiceActivityOptions> = {
  prerollMs: 300,
  minSpeechMs: 180,
  maxCandidateMs: 1200,
  candidateSilenceMs: 320,
  endSilenceMs: 700,
  maxSegmentMs: 50_000,
}

/** 一帧声音的响度（均方根）。 */
export function loudness(frame: Float32Array) {
  let sum = 0
  for (const value of frame) sum += value * value
  return Math.sqrt(sum / Math.max(1, frame.length))
}

export function createVoiceActivity(options: VoiceActivityOptions = {}) {
  const config = { ...DEFAULTS, ...options }
  let phase: Phase = 'idle'
  let noise = 0.008
  let preroll: Array<{ pcm: Uint8Array; ms: number }> = []
  let prerollMs = 0
  let pending: Uint8Array[] = []
  let voicedMs = 0
  let silenceMs = 0
  let durationMs = 0

  function reset() {
    phase = 'idle'; pending = []; voicedMs = 0; silenceMs = 0; durationMs = 0
    preroll = []; prerollMs = 0
  }

  /**
   * 处理一帧。frame 是 16 kHz 的采样，pcm 是同一帧编码后要发给服务端的数据。
   * roleSpeaking 表示角色的声音正在播放。
   */
  function feed(frame: Float32Array, pcm: Uint8Array, roleSpeaking: boolean): VoiceFrameResult {
    const rms = loudness(frame)
    const frameMs = frame.length / 16
    const speechLevel = roleSpeaking ? Math.max(0.06, noise * 4.5) : Math.max(0.03, noise * 3)
    const quietLevel = Math.max(0.012, noise * 1.6)
    const voiced = rms > speechLevel
    const quiet = rms <= quietLevel
    const result: VoiceFrameResult = { send: [], started: false, ended: null }

    if (phase === 'idle') {
      // 安静的时候慢慢跟上环境噪声；角色说话时不跟，免得把它的声音当成环境。
      if (!roleSpeaking && !voiced) {
        noise += (rms - noise) * (rms < noise ? 0.2 : 0.03)
        noise = Math.min(0.05, Math.max(0.002, noise))
      }
      if (!voiced) {
        preroll.push({ pcm, ms: frameMs }); prerollMs += frameMs
        while (preroll.length > 1 && prerollMs - preroll[0].ms >= config.prerollMs) prerollMs -= preroll.shift()!.ms
        return result
      }
      phase = 'candidate'
      pending = [...preroll.map((item) => item.pcm), pcm]
      durationMs = prerollMs + frameMs
      voicedMs = frameMs; silenceMs = 0
      preroll = []; prerollMs = 0
    } else if (phase === 'candidate') {
      pending.push(pcm); durationMs += frameMs
      if (voiced) { voicedMs += frameMs; silenceMs = 0 } else if (quiet) silenceMs += frameMs
      // 一下就过去的爆音，或者拖了很久都不像说话的嗡嗡声：丢掉，什么都不发。
      if (silenceMs >= config.candidateSilenceMs || (durationMs >= config.maxCandidateMs && voicedMs < config.minSpeechMs)) {
        reset()
        return result
      }
    } else {
      result.send.push(pcm); durationMs += frameMs
      if (quiet) silenceMs += frameMs
      else silenceMs = 0
      if (silenceMs >= config.endSilenceMs || durationMs >= config.maxSegmentMs) {
        result.ended = Math.round(durationMs)
        reset()
      }
      return result
    }

    if (phase === 'candidate' && voicedMs >= config.minSpeechMs) {
      phase = 'speech'
      result.started = true
      result.send = pending
      pending = []
      silenceMs = 0
    }
    return result
  }

  /** 挂断时调用：已经确认在说的这半句要交出去，返回它的时长；还没确认的就不要了。 */
  function finish(): number | null {
    const ended = phase === 'speech' ? Math.round(durationMs) : null
    reset()
    return ended
  }

  return {
    feed,
    finish,
    reset,
    /** 现在是不是确认在说话。 */
    speaking: () => phase === 'speech',
    /** 当前估计的环境噪声，测试用。 */
    noiseLevel: () => noise,
  }
}

export type VoiceActivity = ReturnType<typeof createVoiceActivity>
