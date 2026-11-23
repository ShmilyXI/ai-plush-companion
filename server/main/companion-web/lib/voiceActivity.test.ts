import { describe, expect, it } from 'vitest'
import { createVoiceActivity } from './voiceActivity'

/** 一帧 40 毫秒的声音，每个采样的幅度都是 volume。pcm 里放帧号，方便检查发出去的顺序。 */
let counter = 0
function frame(volume: number) {
  counter += 1
  return { samples: new Float32Array(640).fill(volume), pcm: new Uint8Array([counter % 256]) }
}

function feedMany(vad: ReturnType<typeof createVoiceActivity>, volume: number, count: number, roleSpeaking = false) {
  const results = []
  for (let index = 0; index < count; index += 1) {
    const { samples, pcm } = frame(volume)
    results.push(vad.feed(samples, pcm, roleSpeaking))
  }
  return results
}

describe('voice activity', () => {
  it('sends nothing while it is quiet', () => {
    const vad = createVoiceActivity()
    const results = feedMany(vad, 0.002, 100)
    expect(results.every((item) => item.send.length === 0 && !item.started && item.ended === null)).toBe(true)
  })

  it('drops a short burst such as a cough or a knock without sending anything', () => {
    const vad = createVoiceActivity()
    feedMany(vad, 0.003, 20)
    const burst = [...feedMany(vad, 0.4, 2), ...feedMany(vad, 0.003, 20)]
    expect(burst.some((item) => item.started || item.send.length > 0 || item.ended !== null)).toBe(false)
    expect(vad.speaking()).toBe(false)
  })

  it('confirms speech that lasts, sends what was said just before it, and ends the sentence after a pause', () => {
    const vad = createVoiceActivity()
    feedMany(vad, 0.003, 20)
    const speech = feedMany(vad, 0.15, 10)
    const started = speech.findIndex((item) => item.started)
    // 180 毫秒的声音才确认，也就是第 5 帧。确认时把之前留着的至少 300 毫秒（8 帧）和疑似开口的几帧一起发出去。
    expect(started).toBe(4)
    expect(speech[started].send.length).toBe(8 + 5)
    expect(speech.slice(started + 1).every((item) => item.send.length === 1)).toBe(true)

    const pause = feedMany(vad, 0.003, 30)
    const ended = pause.findIndex((item) => item.ended !== null)
    // 安静 700 毫秒算说完。
    expect(ended).toBe(17)
    expect(pause[ended].ended).toBeGreaterThan(700)
    expect(pause.slice(ended + 1).every((item) => item.send.length === 0)).toBe(true)
  })

  it('raises the bar in a noisy room and while the role itself is speaking', () => {
    const noisy = createVoiceActivity()
    // 持续的环境噪声把门槛抬上去，同样大小的声音不再算开口。
    feedMany(noisy, 0.012, 200)
    expect(noisy.noiseLevel()).toBeGreaterThan(0.01)
    expect(feedMany(noisy, 0.032, 20).some((item) => item.started)).toBe(false)

    const echo = createVoiceActivity()
    feedMany(echo, 0.003, 20)
    // 角色说话时，扬声器漏进来的声音（这里是 0.05）不会把角色自己打断；真的开口说话（0.2）可以。
    expect(feedMany(echo, 0.05, 20, true).some((item) => item.started)).toBe(false)
    feedMany(echo, 0.003, 20, true)
    expect(feedMany(echo, 0.2, 6, true).some((item) => item.started)).toBe(true)
  })

  it('hands over half a sentence when the call ends, but not an unconfirmed noise', () => {
    const vad = createVoiceActivity()
    feedMany(vad, 0.15, 8)
    expect(vad.finish()).toBeGreaterThan(0)
    feedMany(vad, 0.15, 2)
    expect(vad.finish()).toBeNull()
  })
})
