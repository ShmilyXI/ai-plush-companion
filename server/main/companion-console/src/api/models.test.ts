import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from './http'
import {
  copyModel,
  createPrivateModel,
  getGlobalModelConfig,
  getPrivateModel,
  listModelCatalog,
  listModelTemplates,
  listPrivateModels,
  testPrivateModel,
  testGlobalModelConfig,
  saveGlobalModelConfig,
  updatePrivateModel,
} from './models'

const validModel = {
  id: 'p1', modelType: 'LLM', name: '我的模型', providerCode: 'openai',
  vendorName: 'OpenAI', protocol: 'OpenAI 兼容', credentialRequired: true,
  apiUrl: 'https://api.example/v1', modelId: 'gpt-x', config: { temperature: 0.3 },
  providerTemplateId: 'SYSTEM_LLM_openai', source: 'private', configuredSecretKeys: ['api_key'],
  apiKeyConfigured: true, enabled: true, usageCount: 1,
}

const validCatalog = {
  id: 'g1', reference: 'global:g1', modelType: 'LLM', name: '系统模型', providerCode: 'openai',
  vendorCode: 'deepseek', vendorName: 'DeepSeek', protocol: 'OpenAI 兼容',
  providerTemplateId: 'SYSTEM_LLM_openai', apiUrl: 'https://api.deepseek.com', modelId: 'deepseek-chat',
  credentialRequirement: 'required', credentialConfigured: false, credentialStatus: 'missing',
  keyUrl: 'https://platform.deepseek.com/api_keys', docsUrl: 'https://api-docs.deepseek.com/',
  setupGuide: ['登录开放平台', '创建 API Key'],
  credentialFields: [
    { key: 'api_key', label: 'API Key', type: 'string', required: true, secret: true, options: [], defaultValue: null },
  ],
  unavailableReason: null, source: 'global', enabled: true,
  defaultModel: true, usageCount: 2, actions: ['view', 'configure', 'test', 'copy'],
}

const validTemplate = {
  id: 'SYSTEM_LLM_openai', modelType: 'LLM', providerCode: 'openai', name: 'OpenAI 接口', sort: 1,
  fields: [
    { key: 'base_url', label: '基础 URL', type: 'string', required: true, secret: false, options: [], defaultValue: null },
    { key: 'api_key', label: 'API Key', type: 'string', required: true, secret: true, options: [], defaultValue: null },
  ],
}

describe('private model API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('strictly parses private model rows', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: [validModel] }, config: {} })

    await expect(listPrivateModels('LLM')).resolves.toEqual([validModel])
    expect(http.get).toHaveBeenCalledWith('/companion/models', { params: { modelType: 'LLM' } })
  })

  it('loads one private model with an encoded id', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: validModel }, config: {} })
    await expect(getPrivateModel('p/1')).resolves.toEqual(validModel)
    expect(http.get).toHaveBeenCalledWith('/companion/models/p%2F1', undefined)
  })

  it.each([
    [{ ...validModel, apiKeyConfigured: 'true' }],
    [{ ...validModel, enabled: 1 }],
    [{ ...validModel, usageCount: -1 }],
    [{ ...validModel, modelType: 'IMAGE' }],
  ])('rejects malformed private model rows %#', async (data) => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data }, config: {} })
    await expect(listPrivateModels('LLM')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it('omits a blank API key when updating and supports saved-model testing', async () => {
    vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: validModel }, config: {} })
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data: { success: true, message: '连接成功', elapsedMillis: 32 } }, config: {} })
    const input = { modelType: 'LLM' as const, name: '我的模型', vendorName: 'OpenAI', protocol: 'OpenAI 兼容',
      providerCode: 'openai', credentialRequired: true, apiUrl: 'https://api.example/v1', apiKey: '', modelId: 'gpt-x', enabled: true }

    await updatePrivateModel('p/1', input)
    await expect(testPrivateModel('p/1', input)).resolves.toEqual({ success: true, message: '连接成功', elapsedMillis: 32 })

    expect(http.put).toHaveBeenCalledWith('/companion/models/p%2F1', expect.not.objectContaining({ apiKey: expect.anything() }), undefined)
    expect(http.post).toHaveBeenCalledWith('/companion/models/p%2F1/test', expect.not.objectContaining({ apiKey: expect.anything() }), undefined)
  })

  it('creates a private model with an explicit key', async () => {
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data: validModel }, config: {} })
    await createPrivateModel({ modelType: 'LLM', name: '我的模型', vendorName: 'OpenAI', protocol: 'OpenAI 兼容',
      providerCode: 'openai', credentialRequired: true, apiKey: 'new-key', enabled: true })
    expect(http.post).toHaveBeenCalledWith('/companion/models', expect.objectContaining({ apiKey: 'new-key', enabled: 1 }), undefined)
  })

  it('parses source-aware catalog rows and requests the management view', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: [validCatalog] }, config: {} })

    await expect(listModelCatalog('LLM')).resolves.toEqual([validCatalog])
    expect(http.get).toHaveBeenCalledWith('/companion/models/catalog', {
      params: { modelType: 'LLM', view: 'management' },
    })
  })

  it('normalizes string-encoded usage counts returned by the Java API', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({
      data: { code: 0, msg: 'success', data: [{ ...validCatalog, usageCount: '2' }] },
      config: {},
    })

    await expect(listModelCatalog('LLM')).resolves.toEqual([validCatalog])
  })

  it.each([
    [{ ...validCatalog, source: 'admin' }],
    [{ ...validCatalog, actions: ['view', 'rotate-secret'] }],
    [{ ...validCatalog, reference: 'g1' }],
    [{ ...validCatalog, credentialStatus: 'ready' }],
    [{ ...validCatalog, keyUrl: 'file:///tmp/key' }],
    [{ ...validCatalog, credentialFields: [{ key: 'api_key', label: '', type: 'string', required: true, secret: true, options: [] }] }],
  ])('rejects malformed catalog rows %#', async (data) => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data }, config: {} })
    await expect(listModelCatalog('LLM')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it('parses provider templates and rejects unknown field types', async () => {
    vi.spyOn(http, 'get').mockResolvedValueOnce({ data: { code: 0, msg: 'success', data: [validTemplate] }, config: {} })
    await expect(listModelTemplates('LLM')).resolves.toEqual([validTemplate])

    vi.spyOn(http, 'get').mockResolvedValueOnce({
      data: { code: 0, msg: 'success', data: [{ ...validTemplate, fields: [{ ...validTemplate.fields[0], type: 'file' }] }] },
      config: {},
    })
    await expect(listModelTemplates('LLM')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it('copies a source-aware model and sends generalized secret changes', async () => {
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data: validModel }, config: {} })
    vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: validModel }, config: {} })

    await expect(copyModel('global:g1', '我的模型')).resolves.toEqual(validModel)
    await updatePrivateModel('p1', {
      modelType: 'LLM', name: '我的模型', vendorName: 'OpenAI', protocol: 'OpenAI 兼容',
      providerCode: 'openai', credentialRequired: true, providerTemplateId: 'SYSTEM_LLM_openai',
      secrets: { api_key: 'new-key', api_secret: 'new-secret' }, clearSecretKeys: ['old_token'], enabled: true,
    })

    expect(http.post).toHaveBeenCalledWith('/companion/models/copy', { reference: 'global:g1', name: '我的模型' }, undefined)
    expect(http.put).toHaveBeenCalledWith('/companion/models/p1', expect.objectContaining({
      providerTemplateId: 'SYSTEM_LLM_openai', secrets: { api_key: 'new-key', api_secret: 'new-secret' },
      clearSecretKeys: ['old_token'],
    }), undefined)
  })

  it('loads saves and tests account-scoped global model configuration with encoded ids', async () => {
    const configured = {
      globalModelId: 'LLM_DeepSeekLLM', apiUrl: 'https://api.deepseek.com', modelId: 'deepseek-chat',
      configuredSecretKeys: ['api_key'], credentialRequirement: 'required', credentialConfigured: true,
      credentialStatus: 'configured',
    }
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: configured }, config: {} })
    vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: configured }, config: {} })
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data: {
      success: true, message: '连接成功', elapsedMillis: 8,
    } }, config: {} })
    const input = { apiUrl: 'https://proxy.example/v1', modelId: 'deepseek-chat',
      secrets: { api_key: 'secret' }, clearSecretKeys: [] }

    await expect(getGlobalModelConfig('LLM/DeepSeek')).resolves.toEqual(configured)
    await expect(saveGlobalModelConfig('LLM/DeepSeek', input)).resolves.toEqual(configured)
    await expect(testGlobalModelConfig('LLM/DeepSeek', input)).resolves.toEqual({ success: true, message: '连接成功', elapsedMillis: 8 })
    expect(http.get).toHaveBeenCalledWith('/companion/models/global/LLM%2FDeepSeek/config', undefined)
    expect(http.put).toHaveBeenCalledWith('/companion/models/global/LLM%2FDeepSeek/config', input, undefined)
    expect(http.post).toHaveBeenCalledWith('/companion/models/global/LLM%2FDeepSeek/test', input, undefined)
  })

  it('rejects global configuration responses that expose secret material', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: {
      globalModelId: 'g1', apiUrl: null, modelId: null, configuredSecretKeys: ['api_key'],
      credentialRequirement: 'required', credentialConfigured: true, credentialStatus: 'configured',
      secrets: { api_key: 'plain-secret' },
    } }, config: {} })

    await expect(getGlobalModelConfig('g1')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })
})
