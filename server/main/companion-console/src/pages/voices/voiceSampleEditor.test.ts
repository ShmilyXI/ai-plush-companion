import { describe, expect, it, vi } from 'vitest'

import {
  MAX_DECODED_PCM_BYTES,
  assertSafeDecodedBuffer,
  encodeVoiceSampleWav,
  readSafeVoiceMetadata,
} from './voiceSampleEditor'

function buffer(overrides: Partial<AudioBuffer> = {}) {
  const data = new Float32Array(120)
  return { duration: 12, length: 120, sampleRate: 10, numberOfChannels: 1,
    getChannelData: () => data, ...overrides } as AudioBuffer
}

describe('voice sample editor', () => {
  it('reads metadata before decode and always clears the object URL and handlers', async () => {
    const media = { duration: 12, preload: '', src: '', onloadedmetadata: null as null | (() => void),
      onerror: null as null | (() => void), removeAttribute: vi.fn(), load: vi.fn() }
    media.load.mockImplementation(() => queueMicrotask(() => media.onloadedmetadata?.()))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:sample')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)

    await expect(readSafeVoiceMetadata(new File(['x'], 'x.mp3'), undefined, () => media as unknown as HTMLAudioElement)).resolves.toBe(12)
    expect(revoke).toHaveBeenCalledWith('blob:sample')
    expect(media.onloadedmetadata).toBeNull()
    expect(media.onerror).toBeNull()
    expect(media.removeAttribute).toHaveBeenCalledWith('src')
  })

  it.each([7.9, 60.1])('rejects unsafe source duration %s before decode', async (duration) => {
    const media = { duration, preload: '', src: '', onloadedmetadata: null as null | (() => void),
      onerror: null as null | (() => void), removeAttribute: vi.fn(), load: vi.fn() }
    media.load.mockImplementation(() => queueMicrotask(() => media.onloadedmetadata?.()))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:sample')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    await expect(readSafeVoiceMetadata(new File(['x'], 'x.mp3'), undefined, () => media as unknown as HTMLAudioElement))
      .rejects.toThrow('音频时长必须在 8 到 60 秒之间')
  })

  it('rejects decoded PCM and encoded WAV above explicit memory and upload limits', () => {
    expect(() => assertSafeDecodedBuffer(buffer({ length: MAX_DECODED_PCM_BYTES / 4 + 1 }))).toThrow('解码后的音频过大')
    expect(() => encodeVoiceSampleWav(buffer({ length: 6 * 1024 * 1024, numberOfChannels: 1 }))).toThrow('WAV 音频不能超过 10MB')
  })

  it('encodes every accepted buffer as WAV', () => {
    const file = encodeVoiceSampleWav(buffer())
    expect(file.name).toBe('voice-sample.wav')
    expect(file.type).toBe('audio/wav')
  })
})
