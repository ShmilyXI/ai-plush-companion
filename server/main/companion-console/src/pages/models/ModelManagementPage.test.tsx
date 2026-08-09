import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as modelApi from '../../api/xiaozhiModels'
import { ModelManagementPage } from './ModelManagementPage'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

vi.mock('../../api/xiaozhiModels', async () => {
  const actual = await vi.importActual<typeof import('../../api/xiaozhiModels')>('../../api/xiaozhiModels')
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
    { key: 'stream', label: '流式输出', type: 'boolean', default: true },
    { key: 'headers', label: '请求头', type: 'dict', default: {} },
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

function renderPage() {
  return render(<MemoryRouter><ModelManagementPage /></MemoryRouter>)
}

describe('ModelManagementPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(modelApi.listModelConfigs).mockImplementation(async ({ modelType }) => ({
      total: 1,
      list: modelType === 'TTS' ? [ttsModel] : modelType === 'LLM' ? [llmModel] : [],
    }))
    vi.mocked(modelApi.getModelConfig).mockResolvedValue(llmModel)
    vi.mocked(modelApi.listProviderTypes).mockResolvedValue(providers)
    vi.mocked(modelApi.createModelConfig).mockResolvedValue(llmModel)
    vi.mocked(modelApi.updateModelConfig).mockResolvedValue(llmModel)
    vi.mocked(modelApi.setModelEnabled).mockResolvedValue(undefined)
    vi.mocked(modelApi.setDefaultModel).mockResolvedValue(undefined)
    vi.mocked(modelApi.deleteModelConfig).mockResolvedValue(undefined)
    vi.mocked(modelApi.testModelConfig).mockResolvedValue({ success: true, elapsedMillis: 18, message: '连接成功' })
  })

  it('loads the native catalog and shows all six model type tabs', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: '模型管理' })).toBeInTheDocument()
    for (const label of ['对话模型 LLM', '视觉模型 VLLM', '语音合成 TTS', '语音识别 ASR', '语音活动检测 VAD', '记忆模型 Memory']) {
      expect(screen.getByRole('tab', { name: label })).toBeInTheDocument()
    }
    expect(await screen.findByText('深度求索')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '设为默认' })).toBeEnabled()
    expect(modelApi.listModelConfigs).toHaveBeenCalledWith(
      { modelType: 'LLM', page: 1, limit: 10 },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
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
    expect(await within(drawer).findByRole('alert')).toHaveTextContent('连接成功 · 18 ms')
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
    expect(await within(drawer).findByRole('alert')).toHaveTextContent('连接成功')

    await user.type(within(drawer).getByLabelText('备注'), '新内容')

    expect(within(drawer).queryByRole('alert')).not.toBeInTheDocument()
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
      { modelType: 'LLM', page: 1, limit: 10 },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ))
    expect(await screen.findByText('模型 0')).toBeInTheDocument()
  })

  it('links every TTS row to its voice management view', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('tab', { name: '语音合成 TTS' }))
    expect(await screen.findByText('Edge TTS')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '管理音色' })).toHaveAttribute(
      'href',
      '/admin/voices?ttsModelId=TTS_EdgeTTS',
    )
  })
})
