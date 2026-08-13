import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from './http'
import { listVolcengineVoices } from './volcengineVoices'

function response(data: unknown, code = 0, msg = 'success') {
  return { data: { code, msg, data }, config: {} }
}

const voice = {
  id: 'voice-a', name: '温柔女声', voiceType: 'voice-a', gender: '女', age: '青年',
  languages: '中文、英文', tags: ['热门'], description: '温柔自然', trialUrl: 'https://example.com/a.mp3',
}

describe('Volcengine voice API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('requests and parses the read-only voice catalog', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ total: 1, list: [voice] }))

    await expect(listVolcengineVoices({ resourceId: 'seed-tts-1.0', page: 1, limit: 20, name: '' }))
      .resolves.toEqual({ total: 1, list: [voice] })
    expect(http.get).toHaveBeenCalledWith('/volcengine/voices', {
      params: { resourceId: 'seed-tts-1.0', page: 1, limit: 20, name: '' },
    })
  })

  it('rejects malformed rows and business errors', async () => {
    vi.spyOn(http, 'get').mockResolvedValueOnce(response({ total: 1, list: [{ ...voice, tags: '热门' }] }))
    await expect(listVolcengineVoices({ resourceId: 'seed-tts-2.0', page: 1, limit: 20 }))
      .rejects.toMatchObject({ name: 'ApiProtocolError' })

    vi.spyOn(http, 'get').mockResolvedValueOnce(response(null, 500, '火山请求失败'))
    await expect(listVolcengineVoices({ resourceId: 'seed-tts-2.0', page: 1, limit: 20 }))
      .rejects.toMatchObject({ name: 'ApiError', message: '火山请求失败' })
  })
})
