import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiProtocolError } from './devices'
import http, { ApiError } from './http'
import {
  addChannelModels,
  createModelChannel,
  listChannelModels,
  listModelChannels,
  listUpstreamModels,
  testModelChannel,
  updateChannelModel,
  updateModelChannel,
} from './modelChannels'

const channel = {
  id: 'c1',
  name: 'DeepSeek',
  vendorCode: 'deepseek',
  providerCode: 'openai',
  baseUrl: 'https://api.deepseek.com',
  credentialConfigured: true,
  modelCount: 1,
  remark: null,
  sort: 0,
}

const channelModel = {
  id: 'm1',
  channelId: 'c1',
  modelType: 'LLM',
  modelId: 'deepseek-chat',
  modelName: 'DeepSeek / deepseek-chat',
  isEnabled: 1,
  isDefault: 0,
  params: { temperature: 1.3, stream_enabled: true, nested: { ignored: true } },
}

function response(data: unknown, code = 0, msg = 'success') {
  return { data: { code, msg, data }, config: {} }
}

describe('model channel API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lists channels without ever exposing a key field', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response([{ ...channel, apiKey: 'should-not-be-read' }]))

    const result = await listModelChannels()

    expect(http.get).toHaveBeenCalledWith('/model-channels', undefined)
    expect(result).toEqual([channel])
    expect(JSON.stringify(result)).not.toContain('should-not-be-read')
  })

  it('rejects malformed channel rows', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response([{ ...channel, providerCode: 'gemini' }]))
    await expect(listModelChannels()).rejects.toBeInstanceOf(ApiProtocolError)
  })

  it('sends the key only when one was typed', async () => {
    vi.spyOn(http, 'post').mockResolvedValue(response(channel))
    vi.spyOn(http, 'put').mockResolvedValue(response(channel))

    await createModelChannel({ name: 'DeepSeek', providerCode: 'openai', baseUrl: 'https://api.deepseek.com', apiKey: 'sk-test' })
    await updateModelChannel('c 1', { name: 'DeepSeek', baseUrl: 'https://api.deepseek.com', apiKey: '' })

    expect(http.post).toHaveBeenCalledWith('/model-channels', {
      name: 'DeepSeek', providerCode: 'openai', baseUrl: 'https://api.deepseek.com', apiKey: 'sk-test',
    }, undefined)
    expect(http.put).toHaveBeenCalledWith('/model-channels/c%201', {
      name: 'DeepSeek', baseUrl: 'https://api.deepseek.com',
    }, undefined)
  })

  it('tests saved and unsaved channels on different endpoints', async () => {
    vi.spyOn(http, 'post').mockResolvedValue(response({ success: true, elapsedMillis: 12, message: '连接成功，发现 2 个模型' }))

    await testModelChannel(null, { providerCode: 'openai', baseUrl: 'https://api.example/v1' })
    const result = await testModelChannel('c1', {})

    expect(http.post).toHaveBeenNthCalledWith(1, '/model-channels/test',
      { providerCode: 'openai', baseUrl: 'https://api.example/v1' }, undefined)
    expect(http.post).toHaveBeenNthCalledWith(2, '/model-channels/c1/test', {}, undefined)
    expect(result).toEqual({ success: true, elapsedMillis: 12, message: '连接成功，发现 2 个模型' })
  })

  it('surfaces the server message when pulling the upstream list fails', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response(null, 500, '密钥无效或没有权限（HTTP 401）'))

    const error = await listUpstreamModels('c1').catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).message).toBe('密钥无效或没有权限（HTTP 401）')
  })

  it('parses upstream and linked models', async () => {
    vi.spyOn(http, 'get')
      .mockResolvedValueOnce(response([{ id: 'deepseek-chat', addedTypes: ['LLM'] }]))
      .mockResolvedValueOnce(response([channelModel]))

    expect(await listUpstreamModels('c1')).toEqual([{ id: 'deepseek-chat', addedTypes: ['LLM'] }])
    expect(await listChannelModels()).toEqual([{
      id: 'm1',
      channelId: 'c1',
      modelType: 'LLM',
      modelId: 'deepseek-chat',
      modelName: 'DeepSeek / deepseek-chat',
      isEnabled: true,
      isDefault: false,
      params: { temperature: 1.3, stream_enabled: true },
    }])
    expect(http.get).toHaveBeenNthCalledWith(1, '/model-channels/c1/upstream-models', undefined)
    expect(http.get).toHaveBeenNthCalledWith(2, '/model-channels/models', undefined)
  })

  it('adds models and updates their parameters', async () => {
    vi.spyOn(http, 'post').mockResolvedValue(response([channelModel]))
    vi.spyOn(http, 'put').mockResolvedValue(response(channelModel))

    await addChannelModels('c1', [{ modelType: 'LLM', modelId: 'deepseek-chat' }])
    await updateChannelModel('m1', { modelName: '陪伴对话', params: { temperature: 0.6, top_k: null } })

    expect(http.post).toHaveBeenCalledWith('/model-channels/c1/models',
      { models: [{ modelType: 'LLM', modelId: 'deepseek-chat' }] }, undefined)
    expect(http.put).toHaveBeenCalledWith('/model-channels/models/m1',
      { modelName: '陪伴对话', params: { temperature: 0.6, top_k: null } }, undefined)
  })
})
