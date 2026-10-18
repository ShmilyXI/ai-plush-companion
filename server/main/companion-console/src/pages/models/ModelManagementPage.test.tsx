import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as modelApi from '../../api/zixuanModels'
import { ModelManagementPage } from './ModelManagementPage'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

vi.mock('../../api/zixuanModels', async () => {
  const actual = await vi.importActual<typeof import('../../api/zixuanModels')>('../../api/zixuanModels')
  return {
    ...actual,
    listModelConfigs: vi.fn(),
    getModelConfig: vi.fn(),
    listProviderTypes: vi.fn(),
    createModelConfig: vi.fn(),
    updateModelConfig: vi.fn(),
    setModelEnabled: vi.fn(),
    setDefaultModel: vi.fn(),
    deleteModelConfig: vi.fn(),
    testModelConfig: vi.fn(),
  }
})

const providers: modelApi.ModelProvider[] = [{
  id: 'SYSTEM_LLM_openai',
  modelType: 'LLM',
  providerCode: 'openai',
  name: 'OpenAI 接口',
  fields: [
    { key: 'base_url', label: '基础地址', type: 'string', default: 'https://api.example/v1' },
    { key: 'api_key', label: 'API Key', type: 'string' },
    { key: 'temperature', label: '温度', type: 'number', default: 0.7 },
    { key: 'max_tokens', label: '最大令牌数', type: 'integer' },
    { key: 'top_p', label: 'top_p值', type: 'float' },
    { key: 'top_k', label: 'top_k值', type: 'integer' },
    { key: 'frequency_penalty', label: '频率惩罚', type: 'float' },
    { key: 'stream', label: '流式输出', type: 'boolean', default: true },
    { key: 'headers', label: '请求头', type: 'dict', default: {} },
  ],
  sort: 1,
  updater: null,
  updateDate: null,
  creator: null,
  createDate: null,
}]

const ttsProviders: modelApi.ModelProvider[] = [{
  id: 'SYSTEM_TTS_edge',
  modelType: 'TTS',
  providerCode: 'edge',
  name: 'Edge TTS',
  fields: [],
  sort: 1,
  updater: null,
  updateDate: null,
  creator: null,
  createDate: null,
}, {
  id: 'SYSTEM_TTS_HSDSTTS',
  modelType: 'TTS',
  providerCode: 'huoshan_double_stream',
  name: '火山引擎',
  fields: [
    { key: 'resource_id', label: '模型版本', type: 'string', options: [
      { label: '语音合成 1.0', value: 'seed-tts-1.0' },
      { label: '语音合成 2.0', value: 'seed-tts-2.0' },
    ] },
    { key: 'appid', label: '应用ID', type: 'string' },
    { key: 'access_token', label: '访问令牌', type: 'password' },
    { key: 'access_key_id', label: 'Access Key ID', type: 'string' },
    { key: 'secret_access_key', label: 'Secret Access Key', type: 'password' },
  ],
  sort: 2,
  updater: null,
  updateDate: null,
  creator: null,
  createDate: null,
}, {
  id: 'SYSTEM_TTS_AliBLStreamTTS',
  modelType: 'TTS',
  providerCode: 'alibl_stream',
  name: '阿里百炼',
  fields: [],
  sort: 3,
  updater: null,
  updateDate: null,
  creator: null,
  createDate: null,
}]

const embeddingProviders: modelApi.ModelProvider[] = [{
  id: 'SYSTEM_Embedding_openai',
  modelType: 'Embedding',
  providerCode: 'openai',
  name: 'OpenAI 兼容 Embedding',
  fields: [
    { key: 'base_url', label: 'Embedding 地址', type: 'string' },
    { key: 'api_key', label: 'Embedding 密钥', type: 'password' },
    { key: 'model_name', label: 'Embedding 模型', type: 'string' },
    { key: 'dimensions', label: '向量维度', type: 'integer', default: 1024 },
    { key: 'send_dimensions', label: '发送 dimensions', type: 'boolean', default: true },
  ],
  sort: 1,
  updater: null,
  updateDate: null,
  creator: null,
  createDate: null,
}]

const memoryProviders: modelApi.ModelProvider[] = [{
  id: 'SYSTEM_Memory_tencentdb',
  modelType: 'Memory',
  providerCode: 'tencentdb',
  name: 'TencentDB Agent Memory',
  fields: [
    { key: 'memory_core_url', label: 'MemoryCore 地址', type: 'string' },
    { key: 'memory_core_api_key', label: 'MemoryCore 密钥', type: 'password' },
    { key: 'llm_model_id', label: '记忆 LLM', type: 'string', options: [
      { label: '智谱 GLM', value: 'LLM_GLM' },
      { label: '智谱 GLM Flash', value: 'LLM_ChatGLMLLM' },
    ] },
    { key: 'embedding_model_id', label: 'Embedding 模型', type: 'string', options: [
      { label: '智谱 Embedding 3', value: 'Embedding_zhipu' },
      { label: '后台 Embedding 3', value: 'Embedding_openai' },
    ] },
  ],
  sort: 1,
  updater: null,
  updateDate: null,
  creator: null,
  createDate: null,
}]

const llmModel: modelApi.ModelConfig = {
  id: 'LLM_DeepSeek',
  modelType: 'LLM',
  modelCode: 'DeepSeekLLM',
  modelName: '深度求索',
  isDefault: 0,
  isEnabled: 1,
  configJson: { type: 'openai', base_url: 'https://old.example/v1', temperature: 0.4 },
  docLink: 'https://docs.example/model',
  remark: '原备注',
  sort: 2,
  configuredSecretPaths: ['api_key'],
}

const secondLlmModel: modelApi.ModelConfig = {
  ...llmModel,
  id: 'LLM_Qwen',
  modelCode: 'QwenLLM',
  modelName: '通义千问',
  configuredSecretPaths: [],
}

const ttsModel: modelApi.ModelConfig = {
  id: 'TTS_EdgeTTS',
  modelType: 'TTS',
  modelCode: 'EdgeTTS',
  modelName: 'Edge TTS',
  isDefault: 1,
  isEnabled: 1,
  configJson: { type: 'edge' },
  docLink: null,
  remark: null,
  sort: 1,
  configuredSecretPaths: [],
}

const memoryModel: modelApi.ModelConfig = {
  id: 'Memory_tencentdb',
  modelType: 'Memory',
  modelCode: 'tencentdb',
  modelName: 'TencentDB Agent Memory',
  isDefault: 0,
  isEnabled: 1,
  configJson: {
    type: 'tencentdb',
    memory_core_url: 'http://host.docker.internal:8420',
    llm_model_id: 'LLM_GLM',
    embedding_model_id: 'Embedding_zhipu',
  },
  docLink: null,
  remark: null,
  sort: 1,
  configuredSecretPaths: ['memory_core_api_key'],
}

const volcengineTtsModel: modelApi.ModelConfig = {
  ...ttsModel,
  id: 'TTS_HuoshanDoubleStreamTTS',
  modelCode: 'HuoshanDoubleStreamTTS',
  modelName: '火山引擎语音合成',
  isDefault: 0,
  configJson: {
    type: 'huoshan_double_stream',
    resource_id: 'seed-tts-1.0',
    appid: 'app-id',
    access_key_id: 'ak-id',
  },
  sort: 2,
  configuredSecretPaths: ['access_token', 'secret_access_key'],
}

const bailianTtsModel: modelApi.ModelConfig = {
  ...ttsModel,
  id: 'TTS_AliBLStreamTTS',
  modelCode: 'AliBLStreamTTS',
  modelName: '阿里百炼语音合成',
  isDefault: 0,
  configJson: { type: 'alibl_stream' },
  sort: 3,
}

function renderPage() {
  return render(<MemoryRouter><ModelManagementPage /></MemoryRouter>)
}

describe('ModelManagementPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(modelApi.listModelConfigs).mockImplementation(async ({ modelType }) => ({
      total: modelType === 'Memory' || modelType === 'LLM' || modelType === 'TTS' ? 1 : 0,
      list: modelType === 'Memory' ? [memoryModel] : modelType === 'TTS' ? [ttsModel] : modelType === 'LLM' ? [llmModel] : [],
    }))
    vi.mocked(modelApi.getModelConfig).mockImplementation(async (id) => id === memoryModel.id ? memoryModel : llmModel)
    vi.mocked(modelApi.listProviderTypes).mockImplementation(async (modelType) => {
      if (modelType === 'TTS') return ttsProviders
      if (modelType === 'Memory') return memoryProviders
      if (modelType === 'Embedding') return embeddingProviders
      return providers
    })
    vi.mocked(modelApi.createModelConfig).mockResolvedValue(llmModel)
    vi.mocked(modelApi.updateModelConfig).mockResolvedValue(llmModel)
    vi.mocked(modelApi.setModelEnabled).mockResolvedValue(undefined)
    vi.mocked(modelApi.setDefaultModel).mockResolvedValue(undefined)
    vi.mocked(modelApi.deleteModelConfig).mockResolvedValue(undefined)
    vi.mocked(modelApi.testModelConfig).mockResolvedValue({ success: true, elapsedMillis: 18, message: '连接成功' })
  })

  it('loads the native catalog and shows all seven model type tabs', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: '模型管理' })).toBeInTheDocument()
    expect(screen.getByText('维护语音识别、对话、视觉、合成和记忆模型')).toBeInTheDocument()
    for (const label of ['对话模型 LLM', '视觉模型 VLLM', '语音合成 TTS', '语音识别 ASR', '语音活动检测 VAD', '记忆模型 Memory', 'Embedding 模型 Embedding']) {
      expect(screen.getByRole('tab', { name: label })).toBeInTheDocument()
    }
    expect(await screen.findByText('深度求索')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '设为默认' })).toBeEnabled()
    expect(modelApi.listModelConfigs).toHaveBeenCalledWith({ modelType: 'LLM', modelName: '', page: 1, limit: 10 })
  })

  it('opens managed Embedding models with OpenAI compatible fields and connection testing', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('tab', { name: 'Embedding 模型 Embedding' }))
    await user.click(await screen.findByRole('button', { name: '新增模型' }))
    const drawer = await screen.findByRole('dialog', { name: '新增模型' })

    expect(within(drawer).getByText('OpenAI 兼容 Embedding')).toBeInTheDocument()
    expect(within(drawer).getByLabelText('Embedding 密钥')).toHaveAttribute('type', 'password')
    expect(within(drawer).getByRole('button', { name: '测试连接' })).toBeInTheDocument()
  })

  it('saves TencentDB model references without referenced credentials', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('tab', { name: '记忆模型 Memory' }))
    await user.click(await screen.findByRole('button', { name: '编辑 TencentDB Agent Memory' }))
    const drawer = await screen.findByRole('dialog', { name: '编辑模型' })

    await user.click(within(drawer).getByRole('combobox', { name: '记忆 LLM' }))
    await user.click((await screen.findAllByText('智谱 GLM Flash')).at(-1)!)
    await user.click(within(drawer).getByRole('combobox', { name: 'Embedding 模型' }))
    await user.click((await screen.findAllByText('后台 Embedding 3')).at(-1)!)
    await user.click(within(drawer).getByRole('button', { name: /保.*存/ }))

    await waitFor(() => expect(modelApi.updateModelConfig).toHaveBeenCalledWith(
      'Memory',
      'tencentdb',
      'Memory_tencentdb',
      expect.objectContaining({
        configJson: expect.objectContaining({
          llm_model_id: 'LLM_ChatGLMLLM',
          embedding_model_id: 'Embedding_openai',
        }),
      }),
    ))
    const config = vi.mocked(modelApi.updateModelConfig).mock.calls.at(-1)?.[3].configJson
    expect(config).not.toHaveProperty('llm_api_key')
    expect(config).not.toHaveProperty('embedding_api_key')
  })

  it('shows whether each model key is configured or unnecessary', async () => {
    vi.mocked(modelApi.listModelConfigs).mockImplementation(async ({ modelType }) => ({
      total: modelType === 'LLM' ? 2 : modelType === 'TTS' ? 1 : 0,
      list: modelType === 'LLM' ? [llmModel, secondLlmModel] : modelType === 'TTS' ? [ttsModel] : [],
    }))
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findByText('已配置')).toBeInTheDocument()
    expect(screen.getByText('未配置')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: '语音合成 TTS' }))
    expect(await screen.findByText('无需配置')).toBeInTheDocument()
  })

  it('requests the first TTS page with the searched model name', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('深度求索')
    await user.type(screen.getByLabelText('模型名称'), '  DeepSeek  ')
    await user.click(screen.getByRole('button', { name: /查\s*询/ }))
    await user.click(screen.getByRole('tab', { name: '语音合成 TTS' }))

    await waitFor(() => expect(modelApi.listModelConfigs).toHaveBeenLastCalledWith({
      modelType: 'TTS',
      modelName: 'DeepSeek',
      page: 1,
      limit: 10,
    }))
  })

  it('shows only Edge, Volcengine, and Alibaba Bailian in TTS management', async () => {
    vi.mocked(modelApi.listModelConfigs).mockImplementation(async ({ modelType }) => ({
      total: modelType === 'TTS' ? 3 : 1,
      list: modelType === 'TTS' ? [ttsModel, volcengineTtsModel, bailianTtsModel] : [llmModel],
    }))
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('tab', { name: '语音合成 TTS' }))

    expect(await screen.findByText('Edge TTS')).toBeInTheDocument()
    expect(screen.getByText('火山引擎语音合成')).toBeInTheDocument()
    expect(screen.getByText('阿里百炼语音合成')).toBeInTheDocument()
    expect(screen.queryByText('火山引擎语音合成 2.0')).not.toBeInTheDocument()
  })

  it('edits the unified Volcengine model with 1.0 and 2.0 without resubmitting saved secrets', async () => {
    vi.mocked(modelApi.listModelConfigs).mockImplementation(async ({ modelType }) => ({
      total: 1,
      list: modelType === 'TTS' ? [volcengineTtsModel] : [llmModel],
    }))
    vi.mocked(modelApi.getModelConfig).mockImplementation(async (id) => id === volcengineTtsModel.id ? volcengineTtsModel : llmModel)
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('tab', { name: '语音合成 TTS' }))
    await user.click(await screen.findByRole('button', { name: '编辑 火山引擎语音合成' }))
    const drawer = await screen.findByRole('dialog', { name: '编辑模型' })

    expect(within(drawer).getByRole('combobox', { name: '供应器' })).toBeDisabled()
    expect(within(drawer).getByText('火山引擎')).toBeInTheDocument()
    expect(within(drawer).getByLabelText('应用ID')).toHaveValue('app-id')
    expect(within(drawer).getByLabelText('访问令牌')).toHaveValue('')
    expect(within(drawer).getByLabelText('Access Key ID')).toHaveValue('ak-id')
    expect(within(drawer).getByLabelText('Secret Access Key')).toHaveValue('')
    expect(within(drawer).getByText('访问令牌 已配置，留空会保留原值')).toBeInTheDocument()
    expect(within(drawer).getByText('Secret Access Key 已配置，留空会保留原值')).toBeInTheDocument()

    await user.click(within(drawer).getByRole('combobox', { name: '模型版本' }))
    expect((await screen.findAllByText('语音合成 1.0')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('语音合成 2.0').length).toBeGreaterThan(0)
    await user.keyboard('{Escape}')

    const name = within(drawer).getByLabelText('模型名称')
    await user.clear(name)
    await user.type(name, '火山引擎 TTS')
    await user.click(within(drawer).getByRole('button', { name: /保.*存/ }))

    await waitFor(() => expect(modelApi.updateModelConfig).toHaveBeenCalledWith(
      'TTS',
      'huoshan_double_stream',
      'TTS_HuoshanDoubleStreamTTS',
      expect.objectContaining({ modelName: '火山引擎 TTS' }),
    ))
    expect(vi.mocked(modelApi.updateModelConfig).mock.calls.at(-1)?.[3]).not.toHaveProperty('configJson')
  })

  it('keeps the latest model type when an older list response arrives late', async () => {
    const pendingLlm = deferred<{ total: number; list: modelApi.ModelConfig[] }>()
    vi.mocked(modelApi.listModelConfigs)
      .mockReturnValueOnce(pendingLlm.promise)
      .mockResolvedValueOnce({ total: 1, list: [ttsModel] })
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(modelApi.listModelConfigs).toHaveBeenCalledTimes(1))
    await user.click(screen.getByRole('tab', { name: '语音合成 TTS' }))
    expect(await screen.findByText('Edge TTS')).toBeInTheDocument()

    await act(async () => {
      pendingLlm.resolve({ total: 1, list: [llmModel] })
      await pendingLlm.promise
    })

    expect(screen.getByText('Edge TTS')).toBeInTheDocument()
    expect(screen.queryByText('深度求索')).not.toBeInTheDocument()
  })

  it('creates a model with fields defined by the selected provider', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '新增模型' }))
    const drawer = await screen.findByRole('dialog', { name: '新增模型' })
    await waitFor(() => expect(modelApi.listProviderTypes).toHaveBeenCalledWith('LLM', expect.anything()))
    expect(within(drawer).getByText('OpenAI 接口')).toBeInTheDocument()
    await user.type(within(drawer).getByLabelText('模型 ID'), 'LLM_Custom')
    await user.type(within(drawer).getByLabelText('模型编码'), 'CustomLLM')
    await user.type(within(drawer).getByLabelText('模型名称'), '自定义模型')
    await user.type(within(drawer).getByLabelText('API Key'), 'new-secret')
    await user.click(within(drawer).getByRole('button', { name: /保.*存/ }))

    await waitFor(() => expect(modelApi.createModelConfig).toHaveBeenCalledWith('LLM', 'openai', expect.objectContaining({
      id: 'LLM_Custom',
      modelCode: 'CustomLLM',
      modelName: '自定义模型',
      isEnabled: 1,
      configJson: expect.objectContaining({
        type: 'openai',
        base_url: 'https://api.example/v1',
        api_key: 'new-secret',
        temperature: 0.7,
        stream: true,
        headers: {},
      }),
    })))
  })

  it('edits a model through its native provider without exposing saved credentials', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '编辑 深度求索' }))
    const drawer = await screen.findByRole('dialog', { name: '编辑模型' })
    expect(modelApi.getModelConfig).toHaveBeenCalledWith('LLM_DeepSeek', expect.anything())
    expect(within(drawer).getByText('API Key 已配置，留空会保留原值')).toBeInTheDocument()
    expect(within(drawer).getByLabelText('API Key')).toHaveValue('')
    const name = within(drawer).getByLabelText('模型名称')
    await user.clear(name)
    await user.type(name, '深度求索新版')
    await user.click(within(drawer).getByRole('button', { name: /保.*存/ }))

    await waitFor(() => expect(modelApi.updateModelConfig).toHaveBeenCalledWith(
      'LLM',
      'openai',
      'LLM_DeepSeek',
      expect.objectContaining({ modelName: '深度求索新版' }),
    ))
    expect(vi.mocked(modelApi.updateModelConfig).mock.calls[0][3]).not.toHaveProperty('configJson')
  })

  it('tests an unsaved model with the current form configuration', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '新增模型' }))
    const drawer = await screen.findByRole('dialog', { name: '新增模型' })
    await user.type(within(drawer).getByLabelText('API Key'), 'new-secret')
    await user.click(within(drawer).getByRole('button', { name: '测试连接' }))

    await waitFor(() => expect(modelApi.testModelConfig).toHaveBeenCalledWith(
      'LLM',
      'openai',
      null,
      expect.objectContaining({ configJson: expect.objectContaining({ api_key: 'new-secret' }) }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ))
    expect(await within(drawer).findByRole('button', { name: '测试成功 · 18 ms' })).toBeEnabled()
    expect(within(drawer).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows testing and failure states on the connection button', async () => {
    const pending = deferred<modelApi.ModelTestResult>()
    vi.mocked(modelApi.testModelConfig).mockReturnValueOnce(pending.promise)
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '编辑 深度求索' }))
    const drawer = await screen.findByRole('dialog', { name: '编辑模型' })
    await user.click(within(drawer).getByRole('button', { name: '测试连接' }))
    expect(within(drawer).getByRole('button', { name: '测试中' })).toBeInTheDocument()

    await act(async () => {
      pending.resolve({ success: false, elapsedMillis: 12, message: '密钥无效' })
      await pending.promise
    })

    expect(await within(drawer).findByRole('button', { name: '测试失败' })).toHaveAttribute('title', '密钥无效')
  })

  it('fills safe recommended defaults for a new LLM model while leaving top_k empty', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '新增模型' }))
    const drawer = await screen.findByRole('dialog', { name: '新增模型' })

    expect(within(drawer).getByLabelText('温度')).toHaveValue('0.7')
    expect(within(drawer).getByLabelText('最大令牌数')).toHaveValue('2048')
    expect(within(drawer).getByLabelText('top_p值')).toHaveValue('1')
    expect(within(drawer).getByLabelText('top_k值')).toHaveValue('')
    expect(within(drawer).getByLabelText('频率惩罚')).toHaveValue('0')
  })

  it.each([
    ['TTS', '语音合成 TTS'],
    ['ASR', '语音识别 ASR'],
    ['VAD', '语音活动检测 VAD'],
  ] as const)('hides connection testing for %s models', async (_, tabName) => {
    vi.mocked(modelApi.listProviderTypes).mockImplementation(async (modelType) => [{
      ...providers[0],
      id: `SYSTEM_${modelType}_test`,
      modelType,
      providerCode: modelType === 'VAD' ? 'silero' : 'openai',
      fields: [],
    }])
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('tab', { name: tabName }))
    await user.click(await screen.findByRole('button', { name: '新增模型' }))
    const drawer = await screen.findByRole('dialog', { name: '新增模型' })

    expect(within(drawer).queryByRole('button', { name: '测试连接' })).not.toBeInTheDocument()
  })

  it('shows connection testing for TencentDB memory', async () => {
    vi.mocked(modelApi.listProviderTypes).mockImplementation(async (modelType) => [{
      ...providers[0],
      id: 'SYSTEM_Memory_tencentdb',
      modelType,
      providerCode: 'tencentdb',
      name: 'TencentDB Agent Memory',
      fields: [],
    }])
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('tab', { name: '记忆模型 Memory' }))
    await user.click(await screen.findByRole('button', { name: '新增模型' }))
    const drawer = await screen.findByRole('dialog', { name: '新增模型' })

    expect(within(drawer).getByRole('button', { name: '测试连接' })).toBeInTheDocument()
  })

  it('hides connection testing for a non-OpenAI LLM provider', async () => {
    vi.mocked(modelApi.listProviderTypes).mockResolvedValue([{
      ...providers[0],
      id: 'SYSTEM_LLM_gemini',
      providerCode: 'gemini',
      name: 'Gemini',
    }])
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '新增模型' }))
    const drawer = await screen.findByRole('dialog', { name: '新增模型' })

    expect(within(drawer).queryByRole('button', { name: '测试连接' })).not.toBeInTheDocument()
  })

  it('tests a saved model without sending an empty saved credential', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '编辑 深度求索' }))
    const drawer = await screen.findByRole('dialog', { name: '编辑模型' })
    await user.click(within(drawer).getByRole('button', { name: '测试连接' }))

    await waitFor(() => expect(modelApi.testModelConfig).toHaveBeenCalled())
    const submitted = vi.mocked(modelApi.testModelConfig).mock.calls[0][3]
    expect(submitted.configJson).toMatchObject({ type: 'openai', base_url: 'https://old.example/v1' })
    expect(submitted.configJson).not.toHaveProperty('api_key')
  })

  it('clears an old connection result when the form changes', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '编辑 深度求索' }))
    const drawer = await screen.findByRole('dialog', { name: '编辑模型' })
    await user.click(within(drawer).getByRole('button', { name: '测试连接' }))
    expect(await within(drawer).findByRole('button', { name: '测试成功 · 18 ms' })).toBeInTheDocument()

    await user.type(within(drawer).getByLabelText('备注'), '新内容')

    expect(within(drawer).getByRole('button', { name: '测试连接' })).toBeInTheDocument()
  })

  it('ignores an old connection result after another editor opens', async () => {
    const pending = deferred<modelApi.ModelTestResult>()
    vi.mocked(modelApi.testModelConfig).mockReturnValueOnce(pending.promise)
    vi.mocked(modelApi.listModelConfigs).mockResolvedValue({ total: 2, list: [llmModel, secondLlmModel] })
    vi.mocked(modelApi.getModelConfig).mockImplementation(async (id) => id === secondLlmModel.id ? secondLlmModel : llmModel)
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '编辑 深度求索' }))
    let drawer = await screen.findByRole('dialog', { name: '编辑模型' })
    await user.click(within(drawer).getByRole('button', { name: '测试连接' }))
    await user.click(within(drawer).getByRole('button', { name: /取.*消/ }))
    await user.click(await screen.findByRole('button', { name: '编辑 通义千问' }))
    drawer = await screen.findByRole('dialog', { name: '编辑模型' })

    await act(async () => {
      pending.resolve({ success: true, elapsedMillis: 5, message: '旧连接成功' })
      await pending.promise
    })

    expect(within(drawer).queryByText('旧连接成功')).not.toBeInTheDocument()
    expect(within(drawer).getByLabelText('模型名称')).toHaveValue('通义千问')
  })

  it('shows save failures inside the drawer and keeps edited values', async () => {
    vi.mocked(modelApi.updateModelConfig).mockRejectedValueOnce(new Error('供应器暂时不可用'))
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '编辑 深度求索' }))
    const drawer = await screen.findByRole('dialog', { name: '编辑模型' })
    const name = within(drawer).getByLabelText('模型名称')
    await user.clear(name)
    await user.type(name, '保留这个名称')
    await user.click(within(drawer).getByRole('button', { name: /保.*存/ }))

    expect(await within(drawer).findByRole('alert')).toHaveTextContent('供应器暂时不可用')
    expect(name).toHaveValue('保留这个名称')
  })

  it('does not let an old successful create close a newer editor or refresh its old view', async () => {
    const pending = deferred<modelApi.ModelConfig>()
    vi.mocked(modelApi.createModelConfig).mockReturnValueOnce(pending.promise)
    vi.mocked(modelApi.listModelConfigs).mockImplementation(async ({ modelType }) => ({
      total: modelType === 'LLM' ? 2 : 1,
      list: modelType === 'LLM' ? [llmModel, secondLlmModel] : modelType === 'TTS' ? [ttsModel] : [],
    }))
    vi.mocked(modelApi.getModelConfig).mockImplementation(async (id) => id === secondLlmModel.id ? secondLlmModel : llmModel)
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '新增模型' }))
    let drawer = await screen.findByRole('dialog', { name: '新增模型' })
    await user.type(within(drawer).getByLabelText('模型 ID'), 'LLM_Pending')
    await user.type(within(drawer).getByLabelText('模型编码'), 'PendingLLM')
    await user.type(within(drawer).getByLabelText('模型名称'), '等待保存')
    await user.click(within(drawer).getByRole('button', { name: /保.*存/ }))
    await user.click(within(drawer).getByRole('button', { name: /取.*消/ }))
    await user.click(screen.getByRole('tab', { name: '语音合成 TTS' }))
    await screen.findByText('Edge TTS')
    await user.click(screen.getByRole('tab', { name: '对话模型 LLM' }))
    await user.click(await screen.findByRole('button', { name: '编辑 通义千问' }))
    drawer = await screen.findByRole('dialog', { name: '编辑模型' })
    expect(within(drawer).getByLabelText('模型名称')).toHaveValue('通义千问')

    await act(async () => {
      pending.resolve(llmModel)
      await pending.promise
    })

    expect(modelApi.createModelConfig).toHaveBeenCalled()
    expect(await screen.findByRole('dialog', { name: '编辑模型' })).toBeInTheDocument()
    expect(within(drawer).getByLabelText('模型名称')).toHaveValue('通义千问')
    expect(modelApi.listModelConfigs).toHaveBeenCalledTimes(3)
  })

  it('does not show an old save failure in a newer editor session', async () => {
    let reject!: (reason: Error) => void
    const pending = new Promise<modelApi.ModelConfig>((_, fail) => { reject = fail })
    vi.mocked(modelApi.updateModelConfig).mockReturnValueOnce(pending)
    vi.mocked(modelApi.listModelConfigs).mockResolvedValue({ total: 2, list: [llmModel, secondLlmModel] })
    vi.mocked(modelApi.getModelConfig).mockImplementation(async (id) => id === secondLlmModel.id ? secondLlmModel : llmModel)
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '编辑 深度求索' }))
    let drawer = await screen.findByRole('dialog', { name: '编辑模型' })
    await user.click(within(drawer).getByRole('button', { name: /保.*存/ }))
    await user.click(within(drawer).getByRole('button', { name: /取.*消/ }))
    await user.click(screen.getByRole('button', { name: '编辑 通义千问' }))
    drawer = await screen.findByRole('dialog', { name: '编辑模型' })
    await act(async () => {
      reject(new Error('旧会话保存失败'))
      await pending.catch(() => undefined)
    })

    expect(modelApi.updateModelConfig).toHaveBeenCalled()
    expect(within(drawer).queryByRole('alert')).not.toBeInTheDocument()
    expect(within(drawer).getByLabelText('模型名称')).toHaveValue('通义千问')
  })

  it('toggles enablement and sets a model as default with confirmation', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('switch', { name: '启用 深度求索' }))
    await waitFor(() => expect(modelApi.setModelEnabled).toHaveBeenCalledWith('LLM_DeepSeek', false))
    await user.click(screen.getByRole('button', { name: '设为默认' }))
    const confirm = await waitFor(() => document.querySelector<HTMLButtonElement>('.ant-popconfirm-buttons .ant-btn-primary'))
    expect(confirm).not.toBeNull()
    await user.click(confirm!)
    await waitFor(() => expect(modelApi.setDefaultModel).toHaveBeenCalledWith('LLM_DeepSeek'))
  })

  it('keeps default confirmation failures in the existing page error flow', async () => {
    vi.mocked(modelApi.setDefaultModel).mockRejectedValueOnce(new Error('默认设置失败'))
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '设为默认' }))
    await user.click(await screen.findByRole('button', { name: /确\s*定/ }))

    expect(await screen.findByRole('alert')).toHaveTextContent('默认设置失败')
    expect(screen.getByText('深度求索')).toBeInTheDocument()
    expect(modelApi.listModelConfigs).toHaveBeenCalledTimes(1)
  })

  it('keeps delete confirmation failures in the existing page error flow', async () => {
    vi.mocked(modelApi.deleteModelConfig).mockRejectedValueOnce(new Error('删除模型失败'))
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '删除 深度求索' }))
    await user.click(await screen.findByRole('button', { name: /确\s*定/ }))

    expect(await screen.findByRole('alert')).toHaveTextContent('删除模型失败')
    expect(screen.getByText('深度求索')).toBeInTheDocument()
    expect(modelApi.listModelConfigs).toHaveBeenCalledTimes(1)
  })

  it('does not refresh an old tab after its mutation completes', async () => {
    const pending = deferred<void>()
    vi.mocked(modelApi.setModelEnabled).mockReturnValueOnce(pending.promise)
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('switch', { name: '启用 深度求索' }))
    await user.click(screen.getByRole('tab', { name: '语音合成 TTS' }))
    expect(await screen.findByText('Edge TTS')).toBeInTheDocument()
    pending.resolve()

    await waitFor(() => expect(modelApi.setModelEnabled).toHaveBeenCalled())
    expect(modelApi.listModelConfigs).toHaveBeenCalledTimes(2)
    expect(screen.getByText('Edge TTS')).toBeInTheDocument()
  })

  it('does not refresh an old tab after setting its default model completes', async () => {
    const pending = deferred<void>()
    vi.mocked(modelApi.setDefaultModel).mockReturnValueOnce(pending.promise)
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '设为默认' }))
    const confirm = await waitFor(() => document.querySelector<HTMLButtonElement>('.ant-popconfirm-buttons .ant-btn-primary'))
    await user.click(confirm!)
    await user.click(screen.getByRole('tab', { name: '语音合成 TTS' }))
    expect(await screen.findByText('Edge TTS')).toBeInTheDocument()
    pending.resolve()

    await waitFor(() => expect(modelApi.setDefaultModel).toHaveBeenCalled())
    expect(modelApi.listModelConfigs).toHaveBeenCalledTimes(2)
  })

  it('does not refresh an old tab after deleting its model completes', async () => {
    const pending = deferred<void>()
    vi.mocked(modelApi.deleteModelConfig).mockReturnValueOnce(pending.promise)
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '删除 深度求索' }))
    const confirm = await waitFor(() => document.querySelector<HTMLButtonElement>('.ant-popconfirm-buttons .ant-btn-primary'))
    await user.click(confirm!)
    await user.click(screen.getByRole('tab', { name: '语音合成 TTS' }))
    expect(await screen.findByText('Edge TTS')).toBeInTheDocument()
    pending.resolve()

    await waitFor(() => expect(modelApi.deleteModelConfig).toHaveBeenCalled())
    expect(modelApi.listModelConfigs).toHaveBeenCalledTimes(2)
  })

  it('moves to the previous page after deleting the only row on a later page', async () => {
    vi.mocked(modelApi.listModelConfigs).mockImplementation(async ({ page: requestedPage }) => requestedPage === 2
      ? { total: 11, list: [llmModel] }
      : { total: 11, list: Array.from({ length: 10 }, (_, index) => ({
        ...llmModel,
        id: `LLM_${index}`,
        modelName: `模型 ${index}`,
      })) })
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('模型 0')
    await user.click(screen.getByTitle('2'))
    await screen.findByText('深度求索')
    await user.click(screen.getByRole('button', { name: '删除 深度求索' }))
    const confirm = await waitFor(() => document.querySelector<HTMLButtonElement>('.ant-popconfirm-buttons .ant-btn-primary'))
    await user.click(confirm!)

    await waitFor(() => expect(modelApi.listModelConfigs).toHaveBeenLastCalledWith(
      { modelType: 'LLM', modelName: '', page: 1, limit: 10 },
    ))
    expect(await screen.findByText('模型 0')).toBeInTheDocument()
  })

  it('does not let a delayed delete take over a newer page of the same model type', async () => {
    const pending = deferred<void>()
    const pendingThirdPage = deferred<{ total: number; list: modelApi.ModelConfig[] }>()
    const thirdPageModel = { ...llmModel, id: 'LLM_Page3', modelName: '第三页模型' }
    vi.mocked(modelApi.deleteModelConfig).mockReturnValueOnce(pending.promise)
    vi.mocked(modelApi.listModelConfigs).mockImplementation(async ({ page: requestedPage }) => requestedPage === 2
      ? { total: 21, list: [llmModel] }
      : requestedPage === 3 ? pendingThirdPage.promise : { total: 21, list: Array.from({ length: 10 }, (_, index) => ({
        ...llmModel,
        id: `LLM_${index}`,
        modelName: `模型 ${index}`,
      })) })
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('模型 0')
    await user.click(screen.getByTitle('2'))
    await screen.findByText('深度求索')
    await user.click(screen.getByRole('button', { name: '删除 深度求索' }))
    await user.click(await screen.findByRole('button', { name: /确\s*定/ }))
    vi.useFakeTimers()
    try {
      fireEvent.click(screen.getByTitle('3'))
      await act(async () => {
        pending.resolve()
        await pending.promise
      })

      expect(screen.getByText('模型已删除')).toBeInTheDocument()
      expect(modelApi.listModelConfigs).toHaveBeenCalledTimes(2)
      await act(async () => { await vi.advanceTimersByTimeAsync(50) })
      expect(modelApi.listModelConfigs).toHaveBeenLastCalledWith({
        modelType: 'LLM',
        modelName: '',
        page: 3,
        limit: 10,
      })

      await act(async () => {
        pendingThirdPage.resolve({ total: 21, list: [thirdPageModel] })
        await pendingThirdPage.promise
      })

      expect(screen.getByText('第三页模型')).toBeInTheDocument()
      expect(modelApi.listModelConfigs).toHaveBeenCalledTimes(3)
    } finally {
      vi.useRealTimers()
    }
  })

  it('links every TTS row to its voice management view', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('tab', { name: '语音合成 TTS' }))
    expect(await screen.findByText('Edge TTS')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '管理音色' })).toHaveAttribute(
      'href',
      '/voices?tab=timbres',
    )
    expect(screen.getByRole('columnheader', { name: '音色' })).toHaveStyle({ width: '96px' })
    expect(screen.getByRole('link', { name: '管理音色' })).toHaveStyle({ whiteSpace: 'nowrap' })
  })
})
