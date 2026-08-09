import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from './http'
import { createTimbre, deleteTimbres, listTimbres, updateTimbre, type TimbreInput } from './timbres'

const timbre = {
  id: 'voice-1',
  ttsModelId: 'TTS_EdgeTTS',
  name: '温柔女声',
  ttsVoice: 'zh-CN-XiaoxiaoNeural',
  languages: 'zh-CN',
  voiceDemo: 'https://example.com/xiaoxiao.mp3',
  remark: null,
  sort: 1,
}

const input: TimbreInput = {
  ttsModelId: 'TTS_EdgeTTS',
  ttsVoice: 'zh-CN-XiaoxiaoNeural',
  name: '温柔女声',
  languages: 'zh-CN',
  sort: 1,
  voiceDemo: 'https://example.com/xiaoxiao.mp3',
  remark: '普通话',
  referenceAudio: 'https://example.com/reference.wav',
  referenceText: '你好',
}

function response(data: unknown, code = 0, msg = 'success') {
  return { data: { code, msg, data }, config: {} }
}

describe('native timbre API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('lists timbres with the exact model filter and strictly parses PageData', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ total: 1, list: [{ ...timbre, sort: '1' }] }))

    await expect(listTimbres({ ttsModelId: 'TTS_EdgeTTS', page: 1, limit: 20, name: '' }))
      .resolves.toEqual({ total: 1, list: [timbre] })
    expect(http.get).toHaveBeenCalledWith('/ttsVoice', {
      params: { ttsModelId: 'TTS_EdgeTTS', page: 1, limit: 20, name: '' },
    })
  })

  it.each([
    [{ total: 1, list: [{ ...timbre, id: '' }] }],
    [{ total: -1, list: [timbre] }],
    [{ total: 1, list: [{ ...timbre, languages: 1 }] }],
    [{ total: 1, list: [{ ...timbre, sort: -1 }] }],
    [{ total: 1, list: [{ ...timbre, sort: '-1' }] }],
    [{ total: 1, list: [{ ...timbre, sort: '1.5' }] }],
    [{ total: 1, list: [{ ...timbre, sort: '9007199254740992' }] }],
    [{ total: 1, list: [{ ...timbre, sort: 'invalid' }] }],
    [{ total: 1, list: [{ ...timbre, voiceDemo: 42 }] }],
  ])('rejects malformed paged timbre data %#', async (data) => {
    vi.spyOn(http, 'get').mockResolvedValue(response(data))

    await expect(listTimbres({ ttsModelId: 'TTS_EdgeTTS', page: 1, limit: 20, name: '' }))
      .rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it('normalizes zero and null sort values from the Java response', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ total: 2, list: [
      { ...timbre, id: 'voice-0', sort: '0' },
      { ...timbre, id: 'voice-null', sort: null },
    ] }))

    await expect(listTimbres({ ttsModelId: 'TTS_EdgeTTS', page: 1, limit: 20, name: '' }))
      .resolves.toMatchObject({ list: [{ sort: 0 }, { sort: null }] })
  })

  it('creates and updates with the exact TimbreDataDTO fields', async () => {
    vi.spyOn(http, 'post').mockResolvedValue(response(null))
    vi.spyOn(http, 'put').mockResolvedValue(response(null))

    await createTimbre({ ...input, ignored: 'secret' } as TimbreInput & { ignored: string })
    await updateTimbre('voice/1', input)

    expect(http.post).toHaveBeenCalledWith('/ttsVoice', input, undefined)
    expect(http.put).toHaveBeenCalledWith('/ttsVoice/voice%2F1', input, undefined)
  })

  it('rejects invalid DTO input before sending a request', async () => {
    const post = vi.spyOn(http, 'post')

    await expect(createTimbre({ ...input, languages: '' })).rejects.toBeInstanceOf(TypeError)
    await expect(createTimbre({ ...input, sort: -1 })).rejects.toBeInstanceOf(RangeError)
    expect(post).not.toHaveBeenCalled()
  })

  it('deletes the selected ids through the native batch endpoint', async () => {
    vi.spyOn(http, 'post').mockResolvedValue(response(null))

    await deleteTimbres(['voice-1', 'voice/2'])

    expect(http.post).toHaveBeenCalledWith('/ttsVoice/delete', ['voice-1', 'voice/2'], undefined)
  })

  it('rejects malformed Result envelopes for mutations', async () => {
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: '0', data: null }, config: {} })

    await expect(createTimbre(input)).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })
})
