import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from './http'
import {
  cloneVoiceAudio,
  getVoiceAudioUuid,
  getVoiceClonePlayUrl,
  listVoiceClones,
  updateVoiceCloneName,
  uploadVoiceSample,
} from './voiceClones'

const clone = {
  id: 'clone-1', name: '温柔声线', modelId: 'tts-1', modelName: '火山双流', voiceId: 'S_voice_1',
  languages: 'zh-CN', userId: '42', userName: 'tester', trainStatus: 3, trainError: '供应商训练失败',
  createDate: '2026-08-08T10:00:00.000+08:00', hasVoice: true,
}

function response(data: unknown, code = 0, msg = 'success') {
  return { data: { code, msg, data }, config: {} }
}

describe('voice clone API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('lists every status including failed training records', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ total: 1, list: [clone] }))
    await expect(listVoiceClones({ page: 1, limit: 10, name: '', orderField: 'create_date', order: 'desc' }))
      .resolves.toEqual({ total: 1, list: [clone] })
    expect(http.get).toHaveBeenCalledWith('/voiceClone', { params: {
      page: 1, limit: 10, name: '', orderField: 'create_date', order: 'desc',
    } })
  })

  it('uploads only MP3 or WAV files through the exact multipart fields', async () => {
    vi.spyOn(http, 'post').mockResolvedValue(response(null))
    const file = new File(['sample'], 'voice.MP3', { type: 'audio/mpeg' })

    await uploadVoiceSample('clone-1', file)

    const body = vi.mocked(http.post).mock.calls[0][1] as FormData
    expect(http.post).toHaveBeenCalledWith('/voiceClone/upload', body, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    expect(body.get('id')).toBe('clone-1')
    expect(body.get('voiceFile')).toBe(file)
  })

  it.each([
    new File(['sample'], 'voice.ogg', { type: 'audio/ogg' }),
    new File(['sample'], 'voice.mp3', { type: 'application/octet-stream' }),
    new File(['sample'], 'voice.wav.exe', { type: 'audio/wav' }),
  ])('rejects a sample when extension or MIME is unsafe %#', async (file) => {
    const post = vi.spyOn(http, 'post')
    await expect(uploadVoiceSample('clone-1', file)).rejects.toBeInstanceOf(TypeError)
    expect(post).not.toHaveBeenCalled()
  })

  it('updates names, obtains a strict UUID, and submits cloning through exact endpoints', async () => {
    const uuid = '123e4567-e89b-42d3-a456-426614174000'
    vi.spyOn(http, 'post')
      .mockResolvedValueOnce(response(null))
      .mockResolvedValueOnce(response(uuid))
      .mockResolvedValueOnce(response(null))

    await updateVoiceCloneName('clone-1', '新名字')
    await expect(getVoiceAudioUuid('clone/1')).resolves.toBe(uuid)
    await cloneVoiceAudio('clone-1')

    expect(http.post).toHaveBeenNthCalledWith(1, '/voiceClone/updateName', { id: 'clone-1', name: '新名字' }, undefined)
    expect(http.post).toHaveBeenNthCalledWith(2, '/voiceClone/audio/clone%2F1', undefined, undefined)
    expect(http.post).toHaveBeenNthCalledWith(3, '/voiceClone/cloneAudio', { cloneId: 'clone-1' }, undefined)
  })

  it('builds the local GET play endpoint only from a valid UUID and safe HTTP base URL', () => {
    const uuid = '123e4567-e89b-42d3-a456-426614174000'
    const oldBase = http.defaults.baseURL
    http.defaults.baseURL = 'https://manager.example/zixuan'
    expect(getVoiceClonePlayUrl(uuid)).toBe(`https://manager.example/zixuan/voiceClone/play/${uuid}`)
    http.defaults.baseURL = 'file:///tmp/manager'
    expect(() => getVoiceClonePlayUrl(uuid)).toThrow(TypeError)
    http.defaults.baseURL = oldBase
    expect(() => getVoiceClonePlayUrl('../secret')).toThrow(TypeError)
  })

  it.each(['not-a-uuid', '', '123e4567-e89b-12d3-a456-426614174000'])('rejects malformed audio UUID %s', async (uuid) => {
    vi.spyOn(http, 'post').mockResolvedValue(response(uuid))
    await expect(getVoiceAudioUuid('clone-1')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it('rejects malformed statuses, URLs in error fields, and string long violations', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ total: 1, list: [{ ...clone, userId: '01' }] }))
    await expect(listVoiceClones({ page: 1, limit: 10, name: '' })).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })
})
