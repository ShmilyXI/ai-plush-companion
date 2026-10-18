import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from './http'
import {
  createModelConfig,
  deleteModelConfig,
  getModelConfig,
  listModelConfigs,
  listModelNames,
  listModelVoices,
  listProviderTypes,
  setDefaultModel,
  setModelEnabled,
  testModelConfig,
  updateModelConfig,
} from './zixuanModels'
import type { CreateModelConfigInput, UpdateModelConfigInput } from './zixuanModels'

const validModel = {
  id: 'LLM_DeepSeek',
  modelType: 'LLM',
  modelCode: 'DeepSeekLLM',
  modelName: '深度求索',
  isDefault: 1 as const,
  isEnabled: 1 as const,
  configJson: { type: 'openai', model_name: 'deepseek-chat' },
  docLink: 'https://api-docs.deepseek.com/',
  remark: null,
  sort: 1,
  configuredSecretPaths: [],
}

const validProviderResponse = {
  id: 'SYSTEM_LLM_openai',
  modelType: 'LLM',
  providerCode: 'openai',
  name: 'OpenAI接口',
  fields: JSON.stringify([
    { key: 'base_url', label: '基础URL', type: 'string', options: ['https://api.example'], editing: true },
    { key: 'temperature', label: '温度', type: 'number', default: 0.7 },
    { key: 'headers', label: '请求头', type: 'dict', dict_name: 'headers' },
  ]),
  sort: 1,
  updater: '1',
  updateDate: '2026-08-08T10:00:00',
  creator: '1',
  createDate: '2026-08-01T10:00:00',
}

const validProvider = {
  ...validProviderResponse,
  fields: [
    { key: 'base_url', label: '基础URL', type: 'string', options: ['https://api.example'] },
    { key: 'temperature', label: '温度', type: 'number', default: 0.7 },
    { key: 'headers', label: '请求头', type: 'dict', dict_name: 'headers' },
  ],
}

const validVoice = {
  id: 'zh-CN-XiaoxiaoNeural',
  name: '晓晓',
  voiceDemo: 'https://example.com/demo.mp3',
  languages: 'zh-CN',
  isClone: false,
}

const createInput: CreateModelConfigInput = {
  id: 'LLM_DeepSeek',
  modelCode: 'DeepSeekLLM',
  modelName: '深度求索',
  isEnabled: 1 as const,
  configJson: { type: 'openai', api_key: 'secret' },
  docLink: 'https://api-docs.deepseek.com/',
  remark: null,
  sort: 1,
}

const updateInput: UpdateModelConfigInput = {
  modelName: '深度求索',
  isEnabled: 1,
  configJson: { type: 'openai', api_key: 'secret' },
  remark: null,
  sort: 1,
}

function response(data: unknown, code = 0, msg = 'success') {
  return { data: { code, msg, data }, config: {} }
}

describe('zixuan model API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('lists model configs with the exact paging query and strictly parses PageData', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ total: 1, list: [validModel] }))

    await expect(listModelConfigs({ modelType: 'LLM', modelName: '深度', page: 2, limit: 20 }))
      .resolves.toEqual({ total: 1, list: [validModel] })
    expect(http.get).toHaveBeenCalledWith('/models/list', {
      params: { modelType: 'LLM', modelName: '深度', page: 2, limit: 20 },
    })
  })

  it.each([
    ['page', 0], ['page', -1], ['page', 1.5], ['page', Number.NaN], ['page', Number.POSITIVE_INFINITY],
    ['page', Number.MAX_SAFE_INTEGER + 1],
    ['limit', 0], ['limit', -1], ['limit', 1.5], ['limit', Number.NaN], ['limit', Number.POSITIVE_INFINITY],
    ['limit', Number.MAX_SAFE_INTEGER + 1],
  ] as const)('rejects invalid %s values before requesting: %s', async (key, value) => {
    const get = vi.spyOn(http, 'get').mockResolvedValue(response({ total: 0, list: [] }))
    const params = { modelType: 'LLM' as const, page: 1, limit: 10, [key]: value }

    await expect(listModelConfigs(params)).rejects.toBeInstanceOf(RangeError)
    expect(get).not.toHaveBeenCalled()
  })

  it('accepts the maximum safe integer for page and limit', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ total: 0, list: [] }))

    await expect(listModelConfigs({
      modelType: 'LLM', page: Number.MAX_SAFE_INTEGER, limit: Number.MAX_SAFE_INTEGER,
    })).resolves.toEqual({ total: 0, list: [] })
  })

  it('lists enabled model names with the controller query', async () => {
    const names = [{ id: 'LLM_DeepSeek', modelName: '深度求索' }]
    vi.spyOn(http, 'get').mockResolvedValue(response(names))

    await expect(listModelNames('LLM', '深度')).resolves.toEqual(names)
    expect(http.get).toHaveBeenCalledWith('/models/names', {
      params: { modelType: 'LLM', modelName: '深度' },
    })
  })

  it('loads voices and one model with encoded ids', async () => {
    vi.spyOn(http, 'get')
      .mockResolvedValueOnce(response([validVoice]))
      .mockResolvedValueOnce(response(validModel))

    await expect(listModelVoices('TTS/model', '晓晓')).resolves.toEqual([validVoice])
    await expect(getModelConfig('LLM/model')).resolves.toEqual(validModel)
    expect(http.get).toHaveBeenNthCalledWith(1, '/models/TTS%2Fmodel/voices', { params: { voiceName: '晓晓' } })
    expect(http.get).toHaveBeenNthCalledWith(2, '/models/LLM%2Fmodel', undefined)
  })

  it('normalizes a null voice list to an empty array', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response(null))

    await expect(listModelVoices('tts1')).resolves.toEqual([])
  })

  it('still rejects a non-array voice list', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ id: 'voice1' }))

    await expect(listModelVoices('tts1')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it('parses provider DTO fields from their JSON string', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response([validProviderResponse]))

    await expect(listProviderTypes('LLM')).resolves.toEqual([validProvider])
    expect(http.get).toHaveBeenCalledWith('/models/LLM/provideTypes', undefined)
  })

  it('accepts managed Embedding providers', async () => {
    const embeddingProvider = {
      ...validProviderResponse,
      id: 'SYSTEM_Embedding_openai',
      modelType: 'Embedding',
      name: 'OpenAI 兼容 Embedding',
    }
    vi.spyOn(http, 'get').mockResolvedValue(response([embeddingProvider]))

    await expect(listProviderTypes('Embedding')).resolves.toMatchObject([{
      modelType: 'Embedding',
      providerCode: 'openai',
    }])
  })

  it('accepts every provider field type used by migrations', async () => {
    const fields = ['string', 'password', 'number', 'integer', 'int', 'float', 'boolean', 'dict']
      .map((type) => ({ key: `field_${type}`, label: type, type }))
    vi.spyOn(http, 'get').mockResolvedValue(response([{ ...validProviderResponse, fields: JSON.stringify(fields) }]))

    await expect(listProviderTypes('LLM')).resolves.toMatchObject([{ fields }])
  })

  it('does not expose defaults or options for credential provider fields', async () => {
    const fields = [{
      key: 'api_key', label: 'API Key', type: 'password',
      default: 'provider-default-secret', options: ['provider-option-secret'],
    }]
    vi.spyOn(http, 'get').mockResolvedValue(response([{ ...validProviderResponse, fields: JSON.stringify(fields) }]))

    const providers = await listProviderTypes('LLM')
    expect(providers[0].fields).toEqual([{ key: 'api_key', label: 'API Key', type: 'password' }])
    expect(JSON.stringify(providers)).not.toMatch(/provider-default-secret|provider-option-secret/)
  })

  it('redacts credential provider values from field protocol errors', async () => {
    const fields = [{ key: 'api_key', label: 'API Key', type: 'file', default: 'provider-error-secret' }]
    vi.spyOn(http, 'get').mockResolvedValue(response([{ ...validProviderResponse, fields: JSON.stringify(fields) }]))

    const error = await listProviderTypes('LLM').catch((caught: unknown) => caught)
    expect(error).toMatchObject({ name: 'ApiProtocolError' })
    expect(JSON.stringify((error as { data: unknown }).data)).not.toContain('provider-error-secret')
  })

  it('does not retain a malformed raw provider field string in protocol errors', async () => {
    const fields = '[{"key":"api_key","default":"raw-provider-secret"}'
    vi.spyOn(http, 'get').mockResolvedValue(response([{ ...validProviderResponse, fields }]))

    const error = await listProviderTypes('LLM').catch((caught: unknown) => caught)
    expect(error).toMatchObject({ name: 'ApiProtocolError' })
    expect(JSON.stringify((error as { data: unknown }).data)).not.toContain('raw-provider-secret')
  })

  it('redacts a non-array provider field document in protocol errors', async () => {
    const fields = JSON.stringify({ api_key: 'non-array-provider-secret' })
    vi.spyOn(http, 'get').mockResolvedValue(response([{ ...validProviderResponse, fields }]))

    const error = await listProviderTypes('LLM').catch((caught: unknown) => caught)
    expect(error).toMatchObject({ name: 'ApiProtocolError' })
    expect(JSON.stringify((error as { data: unknown }).data)).not.toContain('non-array-provider-secret')
  })

  it('creates and updates model configs with encoded route segments', async () => {
    vi.spyOn(http, 'post').mockResolvedValue(response(validModel))
    vi.spyOn(http, 'put').mockResolvedValue(response(validModel))

    await expect(createModelConfig('LLM', 'open/ai', createInput)).resolves.toEqual(validModel)
    await expect(updateModelConfig('LLM', 'open/ai', 'model/id', updateInput)).resolves.toEqual(validModel)
    expect(http.post).toHaveBeenCalledWith('/models/LLM/open%2Fai', createInput, undefined)
    expect(http.put).toHaveBeenCalledWith('/models/LLM/open%2Fai/model%2Fid', updateInput, undefined)
  })

  it('tests an unsaved native model without dropping credentials', async () => {
    vi.spyOn(http, 'post').mockResolvedValue(response({ success: true, elapsedMillis: 21, message: '连接成功' }))
    const input: CreateModelConfigInput = {
      configJson: { type: 'openai', base_url: 'https://api.deepseek.com', api_key: 'secret' },
    }

    await expect(testModelConfig('LLM', 'open/ai', null, input))
      .resolves.toEqual({ success: true, elapsedMillis: 21, message: '连接成功' })

    expect(http.post).toHaveBeenCalledWith('/models/LLM/open%2Fai/test', input, undefined)
  })

  it('tests a saved native model with encoded identifiers', async () => {
    vi.spyOn(http, 'post').mockResolvedValue(response({ success: false, elapsedMillis: 9, message: '服务返回 HTTP 401' }))

    await testModelConfig('LLM', 'open/ai', 'model/id', { configJson: { type: 'openai' } })

    expect(http.post).toHaveBeenCalledWith(
      '/models/LLM/open%2Fai/model%2Fid/test',
      { configJson: { type: 'openai' } },
      undefined,
    )
  })

  it.each([
    { success: true, elapsedMillis: -1, message: '连接成功' },
    { success: 1, elapsedMillis: 1, message: '连接成功' },
    { success: true, elapsedMillis: 1.5, message: '连接成功' },
    { success: true, elapsedMillis: 1, message: null },
  ])('rejects malformed native model test responses %#', async (data) => {
    vi.spyOn(http, 'post').mockResolvedValue(response(data))

    await expect(testModelConfig('LLM', 'openai', null, { configJson: {} }))
      .rejects.toMatchObject({ name: 'ApiProtocolError', message: '模型连接测试数据格式错误' })
  })

  it('does not send fields outside the create and update DTO boundaries', async () => {
    vi.spyOn(http, 'post').mockResolvedValue(response(validModel))
    vi.spyOn(http, 'put').mockResolvedValue(response(validModel))
    const unsafeCreate = { ...createInput, isDefault: 1 } as CreateModelConfigInput
    const unsafeUpdate = {
      ...updateInput,
      id: 'replacement-id',
      modelCode: 'ReplacementCode',
      isDefault: 1,
      docLink: 'https://malicious.example/',
    } as UpdateModelConfigInput

    await createModelConfig('LLM', 'openai', unsafeCreate)
    await updateModelConfig('LLM', 'openai', 'm1', unsafeUpdate)
    expect(http.post).toHaveBeenCalledWith('/models/LLM/openai', createInput, undefined)
    expect(http.put).toHaveBeenCalledWith('/models/LLM/openai/m1', updateInput, undefined)
  })

  it('recursively removes returned credentials and reports only their paths', async () => {
    const unsafeModel = {
      ...validModel,
      configJson: {
        type: 'openai',
        max_tokens: 2048,
        max_new_tokens: 1024,
        api_secret: 'api-secret-value',
        nested: {
          access_token: 'access-token-value',
          api_password: 'api-password-value',
          authorization: 'authorization-value',
          auth: { password: 'password-value' },
          keep: 'safe-value',
        },
      },
    }
    vi.spyOn(http, 'get').mockResolvedValue(response(unsafeModel))

    const model = await getModelConfig('m1')
    expect(model.configJson).toEqual({
      type: 'openai',
      max_tokens: 2048,
      max_new_tokens: 1024,
      nested: { auth: {}, keep: 'safe-value' },
    })
    expect(model.configuredSecretPaths).toEqual([
      'api_secret',
      'nested.access_token',
      'nested.api_password',
      'nested.authorization',
      'nested.auth.password',
    ])
    expect(JSON.stringify(model))
      .not.toMatch(/api-secret-value|access-token-value|api-password-value|authorization-value|password-value/)
  })

  it('does not report blank returned credentials as configured', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({
      ...validModel,
      configJson: {
        type: 'openai',
        api_key: '',
        token: '   ',
        secret_key: null,
        password: '****',
      },
    }))

    const model = await getModelConfig('m1')
    expect(model.configJson).toEqual({ type: 'openai' })
    expect(model.configuredSecretPaths).toEqual(['password'])
  })

  it('redacts credentials from protocol error data for a malformed model', async () => {
    const unsafeModel = {
      ...validModel,
      isEnabled: 2,
      configJson: {
        api_secret: 'bad-api-secret',
        nested: { api_password: 'bad-api-password', authorization: 'bad-authorization', password: 'bad-password' },
      },
    }
    vi.spyOn(http, 'get').mockResolvedValue(response(unsafeModel))

    const error = await getModelConfig('m1').catch((caught: unknown) => caught)
    expect(error).toMatchObject({ name: 'ApiProtocolError' })
    expect(JSON.stringify((error as { data: unknown }).data))
      .not.toMatch(/bad-api-secret|bad-api-password|bad-authorization|bad-password/)
  })

  it('updates enabled/default state and deletes with encoded ids', async () => {
    vi.spyOn(http, 'put').mockResolvedValue(response(null))
    vi.spyOn(http, 'delete').mockResolvedValue(response(null))

    await setModelEnabled('model/id', false)
    await setDefaultModel('model/id')
    await deleteModelConfig('model/id')
    expect(http.put).toHaveBeenNthCalledWith(1, '/models/enable/model%2Fid/0', undefined, undefined)
    expect(http.put).toHaveBeenNthCalledWith(2, '/models/default/model%2Fid', undefined, undefined)
    expect(http.delete).toHaveBeenCalledWith('/models/model%2Fid', undefined)
  })

  it('does not swallow a non-zero Result code', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response(null, 500, '读取失败'))

    await expect(getModelConfig('m1')).rejects.toMatchObject({
      name: 'ApiError', code: 500, message: '读取失败',
    })
  })

  it.each([
    { total: -1, list: [validModel] },
    { total: 1.5, list: [validModel] },
    { total: 1, list: validModel },
  ])('rejects malformed model paging data %#', async (data) => {
    vi.spyOn(http, 'get').mockResolvedValue(response(data))
    await expect(listModelConfigs({ modelType: 'LLM', page: 1, limit: 10 }))
      .rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it.each([
    { ...validModel, modelType: 'IMAGE' },
    { ...validModel, isDefault: true },
    { ...validModel, isEnabled: 2 },
    { ...validModel, configJson: [] },
    { ...validModel, sort: 1.5 },
  ])('rejects malformed model data %#', async (data) => {
    vi.spyOn(http, 'get').mockResolvedValue(response(data))
    await expect(getModelConfig('m1')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it.each([
    [{ ...validVoice, id: '' }],
    [{ ...validVoice, languages: ['zh-CN'] }],
    [{ ...validVoice, isClone: 0 }],
  ])('rejects malformed voice data %#', async (data) => {
    vi.spyOn(http, 'get').mockResolvedValue(response(data))
    await expect(listModelVoices('tts1')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it.each([
    [{ ...validProviderResponse, fields: '{bad json' }],
    [{ ...validProviderResponse, fields: JSON.stringify([{ key: 'api_key', label: '', type: 'string' }]) }],
    [{ ...validProviderResponse, fields: JSON.stringify([{ key: 'api_key', label: 'API Key', type: 1 }]) }],
    [{ ...validProviderResponse, fields: JSON.stringify([{ key: 'api_key', label: 'API Key', type: 'file' }]) }],
  ])('rejects malformed provider fields %#', async (data) => {
    vi.spyOn(http, 'get').mockResolvedValue(response(data))
    await expect(listProviderTypes('LLM')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })
})
