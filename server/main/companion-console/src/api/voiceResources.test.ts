import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from './http'
import {
  createVoiceResources,
  deleteVoiceResources,
  getVoiceResource,
  listAssignableUsers,
  listTtsPlatforms,
  listUserVoiceResources,
  listVoiceResources,
  type VoiceResourceInput,
} from './voiceResources'

const resource = {
  id: 'clone-1', name: '温柔声线', modelId: 'tts-1', modelName: '火山双流', voiceId: 'S_voice_1',
  languages: 'zh-CN', userId: '9007199254740993', userName: 'tester', trainStatus: 0,
  trainError: null, createDate: '2026-08-08T10:00:00.000+08:00', hasVoice: false,
}

function response(data: unknown, code = 0, msg = 'success') {
  return { data: { code, msg, data }, config: {} }
}

describe('voice resource API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('covers the native list and detail endpoints with strict PageData parsing', async () => {
    vi.spyOn(http, 'get')
      .mockResolvedValueOnce(response({ total: '1', list: [resource] }))
      .mockResolvedValueOnce(response(resource))

    await expect(listVoiceResources({ page: 1, limit: 20, name: '温柔', orderField: 'create_date', order: 'desc' }))
      .resolves.toEqual({ total: 1, list: [resource] })
    await expect(getVoiceResource('clone/1')).resolves.toEqual(resource)

    expect(http.get).toHaveBeenNthCalledWith(1, '/voiceResource', { params: {
      page: 1, limit: 20, name: '温柔', orderField: 'create_date', order: 'desc',
    } })
    expect(http.get).toHaveBeenNthCalledWith(2, '/voiceResource/clone%2F1', undefined)
  })

  it('accepts the detail controller nullable hasVoice field without weakening list parsing', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ ...resource, hasVoice: null }))
    await expect(getVoiceResource('clone-1')).resolves.toMatchObject({ id: 'clone-1', hasVoice: null })
  })

  it('posts the exact VoiceCloneDTO and deletes comma-separated resource ids', async () => {
    const input: VoiceResourceInput = {
      modelId: 'tts-1', voiceIds: ['S_one', 'S_two'], userId: '9007199254740993', languages: 'zh-CN',
    }
    vi.spyOn(http, 'post').mockResolvedValue(response(null))
    vi.spyOn(http, 'delete').mockResolvedValue(response(null))

    await createVoiceResources({ ...input, ignored: 'secret' } as VoiceResourceInput & { ignored: string })
    await deleteVoiceResources(['clone-1', 'clone/2'])

    expect(http.post).toHaveBeenCalledWith('/voiceResource', input, undefined)
    expect(http.delete).toHaveBeenCalledWith('/voiceResource/clone-1%2Cclone%2F2', undefined)
  })

  it('covers user resources, TTS platforms, and native admin user lookup', async () => {
    vi.spyOn(http, 'get')
      .mockResolvedValueOnce(response([resource]))
      .mockResolvedValueOnce(response([{ id: 'tts-1', modelName: '火山双流' }]))
      .mockResolvedValueOnce(response({ total: 1, list: [{ userid: '9007199254740993', mobile: '13800138000' }] }))

    await expect(listUserVoiceResources('9007199254740993')).resolves.toEqual([resource])
    await expect(listTtsPlatforms()).resolves.toEqual([{ id: 'tts-1', modelName: '火山双流' }])
    await expect(listAssignableUsers('138', 1, 20)).resolves.toEqual({
      total: 1, list: [{ id: '9007199254740993', mobile: '13800138000' }],
    })

    expect(http.get).toHaveBeenNthCalledWith(1, '/voiceResource/user/9007199254740993', undefined)
    expect(http.get).toHaveBeenNthCalledWith(2, '/voiceResource/ttsPlatforms', undefined)
    expect(http.get).toHaveBeenNthCalledWith(3, '/admin/users', { params: { mobile: '138', page: 1, limit: 20 } })
  })

  it.each([
    { ...resource, userId: 9007199254740992 },
    { ...resource, trainStatus: 4 },
    { ...resource, hasVoice: 'false' },
    { ...resource, trainError: { message: 'secret' } },
    { ...resource, createDate: 123 },
  ])('rejects malformed resource fields %#', async (item) => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ total: 1, list: [item] }))
    await expect(listVoiceResources({ page: 1, limit: 20, name: '' })).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it('rejects malformed pagination and DTO values before requests escape', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ total: '9007199254740992', list: [] }))
    await expect(listVoiceResources({ page: 1, limit: 20, name: '' })).rejects.toMatchObject({ name: 'ApiProtocolError' })

    const post = vi.spyOn(http, 'post')
    await expect(createVoiceResources({ modelId: '', voiceIds: ['S_one'], userId: '1', languages: 'zh-CN' })).rejects.toBeInstanceOf(TypeError)
    await expect(createVoiceResources({ modelId: 'tts-1', voiceIds: [''], userId: '1', languages: 'zh-CN' })).rejects.toBeInstanceOf(TypeError)
    await expect(createVoiceResources({ modelId: 'tts-1', voiceIds: ['S_one'], userId: '1.5', languages: 'zh-CN' })).rejects.toBeInstanceOf(TypeError)
    expect(post).not.toHaveBeenCalled()
  })

  it('rejects numeric Java Long user ids instead of risking precision coercion', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ total: 1, list: [{ ...resource, userId: 42 }] }))
    await expect(listVoiceResources({ page: 1, limit: 20, name: '' })).rejects.toMatchObject({ name: 'ApiProtocolError' })
    await expect(createVoiceResources({ modelId: 'tts-1', voiceIds: ['S_one'], userId: 42 as unknown as string, languages: 'zh-CN' }))
      .rejects.toBeInstanceOf(TypeError)
  })
})
