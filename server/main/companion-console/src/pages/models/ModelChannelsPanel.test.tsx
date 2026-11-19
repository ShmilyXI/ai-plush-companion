import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as channelApi from '../../api/modelChannels'
import * as modelApi from '../../api/zixuanModels'
import { ModelChannelsPanel } from './ModelChannelsPanel'

vi.mock('../../api/modelChannels', async () => {
  const actual = await vi.importActual<typeof import('../../api/modelChannels')>('../../api/modelChannels')
  return {
    ...actual,
    listModelChannels: vi.fn(),
    listChannelModels: vi.fn(),
    createModelChannel: vi.fn(),
    updateModelChannel: vi.fn(),
    deleteModelChannel: vi.fn(),
    testModelChannel: vi.fn(),
    listUpstreamModels: vi.fn(),
    addChannelModels: vi.fn(),
    updateChannelModel: vi.fn(),
  }
})

vi.mock('../../api/zixuanModels', async () => {
  const actual = await vi.importActual<typeof import('../../api/zixuanModels')>('../../api/zixuanModels')
  return {
    ...actual,
    listModelConfigs: vi.fn(),
    listProviderTypes: vi.fn(),
    setDefaultModel: vi.fn(),
    setModelEnabled: vi.fn(),
    deleteModelConfig: vi.fn(),
  }
})

const channel: channelApi.ModelChannel = {
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

const chatModel: channelApi.ChannelModel = {
  id: 'm1',
  channelId: 'c1',
  modelType: 'LLM',
  modelId: 'deepseek-chat',
  modelName: 'DeepSeek / deepseek-chat',
  isEnabled: true,
  isDefault: true,
  params: { temperature: 1.3 },
}

const reasonerModel: channelApi.ChannelModel = {
  ...chatModel,
  id: 'm2',
  modelId: 'deepseek-reasoner',
  modelName: 'DeepSeek / deepseek-reasoner',
  isDefault: false,
}

function defaultRow(id: string, modelName: string, modelType: modelApi.ModelType): modelApi.ModelConfig {
  return {
    id,
    modelType,
    modelCode: id,
    modelName,
    isDefault: 1,
    isEnabled: 1,
    configJson: null,
    docLink: null,
    remark: null,
    sort: 0,
    configuredSecretPaths: [],
  }
}

function mockLists(channels: channelApi.ModelChannel[], models: channelApi.ChannelModel[]) {
  vi.mocked(channelApi.listModelChannels).mockResolvedValue(channels)
  vi.mocked(channelApi.listChannelModels).mockResolvedValue(models)
}

describe('ModelChannelsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(modelApi.listModelConfigs).mockImplementation(async ({ modelType }) => modelType === 'LLM'
      ? { total: 1, list: [defaultRow('m1', 'DeepSeek / deepseek-chat', 'LLM')] }
      : { total: 0, list: [] })
  })

  it('walks from a new connection straight into picking models', async () => {
    const user = userEvent.setup()
    const created = { ...channel, modelCount: 0 }
    mockLists([], [])
    vi.mocked(modelApi.listModelConfigs).mockResolvedValue({ total: 0, list: [] })
    vi.mocked(channelApi.createModelChannel).mockResolvedValue(created)
    vi.mocked(channelApi.listUpstreamModels).mockResolvedValue([
      { id: 'deepseek-chat', addedTypes: [] },
      { id: 'qwen-omni-turbo', addedTypes: [] },
      { id: 'text-embedding-v3', addedTypes: [] },
    ])
    vi.mocked(channelApi.addChannelModels).mockResolvedValue([chatModel])

    render(<ModelChannelsPanel />)
    expect(await screen.findByText('还没有连接，先添加一个服务商')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /添加连接/ }))
    const editor = await screen.findByRole('dialog')
    expect(within(editor).getByLabelText('连接名称')).toHaveValue('DeepSeek')
    expect(within(editor).getByLabelText('接口地址')).toHaveValue('https://api.deepseek.com')
    await user.type(within(editor).getByLabelText('API 密钥'), 'sk-test')
    await user.click(within(editor).getByRole('button', { name: /保.*存/ }))

    await waitFor(() => expect(channelApi.createModelChannel).toHaveBeenCalledWith({
      name: 'DeepSeek',
      vendorCode: 'deepseek',
      providerCode: 'openai',
      baseUrl: 'https://api.deepseek.com',
      apiKey: 'sk-test',
    }))

    const picker = await screen.findByRole('dialog', { name: /从“DeepSeek”导入模型/ })
    expect(channelApi.listUpstreamModels).toHaveBeenCalledWith('c1')
    // 导入时不需要选类型：一个多模态模型勾一次，对话和视觉两种能力一起建好。
    expect(within(picker).queryByRole('combobox')).not.toBeInTheDocument()
    for (const id of ['deepseek-chat', 'qwen-omni-turbo', 'text-embedding-v3']) {
      const row = (await within(picker).findByText(id)).closest('tr') as HTMLElement
      await user.click(within(row).getByRole('checkbox'))
    }
    await user.click(within(picker).getByRole('button', { name: '导入所选（3）' }))

    await waitFor(() => expect(channelApi.addChannelModels).toHaveBeenCalledWith('c1', [
      { modelType: 'LLM', modelId: 'deepseek-chat' },
      { modelType: 'LLM', modelId: 'qwen-omni-turbo' },
      { modelType: 'VLLM', modelId: 'qwen-omni-turbo' },
      { modelType: 'Embedding', modelId: 'text-embedding-v3' },
    ]))
  })

  it('never shows a saved key and keeps it when the field is left blank', async () => {
    const user = userEvent.setup()
    mockLists([channel], [chatModel])
    vi.mocked(channelApi.updateModelChannel).mockResolvedValue({ ...channel, name: 'DeepSeek 主账号' })

    render(<ModelChannelsPanel />)
    await user.click(await screen.findByRole('button', { name: '编辑连接 DeepSeek' }))
    const editor = await screen.findByRole('dialog')
    expect(within(editor).getByLabelText('API 密钥')).toHaveValue('')
    expect(within(editor).getByText('已保存密钥，留空会保留原值')).toBeInTheDocument()
    expect(within(editor).queryByLabelText('服务商')).not.toBeInTheDocument()

    const name = within(editor).getByLabelText('连接名称')
    await user.clear(name)
    await user.type(name, 'DeepSeek 主账号')
    await user.click(within(editor).getByRole('button', { name: /保.*存/ }))

    await waitFor(() => expect(channelApi.updateModelChannel).toHaveBeenCalledTimes(1))
    const [id, input] = vi.mocked(channelApi.updateModelChannel).mock.calls[0]
    expect(id).toBe('c1')
    expect(input).toMatchObject({ name: 'DeepSeek 主账号', providerCode: 'openai', baseUrl: 'https://api.deepseek.com' })
    expect(input.apiKey).toBeUndefined()
  })

  it('protects the default model and connections that still have models', async () => {
    const user = userEvent.setup()
    mockLists([channel], [chatModel, reasonerModel])
    vi.mocked(modelApi.setModelEnabled).mockResolvedValue(undefined)

    render(<ModelChannelsPanel />)
    expect(await screen.findByText('DeepSeek / deepseek-reasoner')).toBeInTheDocument()

    expect(screen.getByRole('button', { name: '删除连接 DeepSeek' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '移除 DeepSeek / deepseek-chat' })).toBeDisabled()
    expect(screen.getByRole('switch', { name: '启用 DeepSeek / deepseek-chat' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '移除 DeepSeek / deepseek-reasoner' })).toBeEnabled()

    await user.click(screen.getByRole('switch', { name: '启用 DeepSeek / deepseek-reasoner' }))
    await waitFor(() => expect(modelApi.setModelEnabled).toHaveBeenCalledWith('m2', false))
  })

  it('still allows adding a model by hand when the upstream list cannot be pulled', async () => {
    const user = userEvent.setup()
    mockLists([channel], [chatModel])
    vi.mocked(channelApi.listUpstreamModels).mockRejectedValue(new Error('这个地址不支持拉取模型列表（HTTP 404），可以手动添加模型'))
    vi.mocked(channelApi.addChannelModels).mockResolvedValue([reasonerModel])

    render(<ModelChannelsPanel />)
    await user.click(await screen.findByRole('button', { name: '拉取 DeepSeek 的模型' }))
    const picker = await screen.findByRole('dialog')
    expect(await within(picker).findByText('这个地址不支持拉取模型列表（HTTP 404），可以手动添加模型')).toBeInTheDocument()

    await user.type(within(picker).getByLabelText('手动填写模型代号'), ' deepseek-reasoner ')
    await user.click(within(picker).getByRole('button', { name: '手动导入' }))

    await waitFor(() => expect(channelApi.addChannelModels).toHaveBeenCalledWith('c1', [
      { modelType: 'LLM', modelId: 'deepseek-reasoner' },
    ]))
  })

  it('shows one row per model and lets its capabilities be changed', async () => {
    const user = userEvent.setup()
    const omniChat: channelApi.ChannelModel = {
      ...chatModel, id: 'o1', modelId: 'qwen-omni-turbo', modelName: '百炼 / qwen-omni-turbo', isDefault: false,
    }
    const omniVision: channelApi.ChannelModel = { ...omniChat, id: 'o2', modelType: 'VLLM', params: {} }
    mockLists([channel], [chatModel, omniChat, omniVision])
    vi.mocked(channelApi.addChannelModels).mockResolvedValue([])
    vi.mocked(modelApi.deleteModelConfig).mockResolvedValue(undefined)

    render(<ModelChannelsPanel />)

    // 同一个模型的对话和视觉两条配置合并成一行。
    expect(await screen.findAllByText('百炼 / qwen-omni-turbo')).toHaveLength(1)
    expect(screen.getByRole('checkbox', { name: '百炼 / qwen-omni-turbo 支持对话' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '百炼 / qwen-omni-turbo 支持视觉' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '百炼 / qwen-omni-turbo 支持向量' })).not.toBeChecked()
    // 默认模型的那种能力不能去掉。
    expect(screen.getByRole('checkbox', { name: 'DeepSeek / deepseek-chat 支持对话' })).toBeDisabled()

    await user.click(screen.getByRole('checkbox', { name: 'DeepSeek / deepseek-chat 支持视觉' }))
    await waitFor(() => expect(channelApi.addChannelModels).toHaveBeenCalledWith('c1', [
      { modelType: 'VLLM', modelId: 'deepseek-chat', modelName: 'DeepSeek / deepseek-chat' },
    ]))

    await user.click(screen.getByRole('checkbox', { name: '百炼 / qwen-omni-turbo 支持视觉' }))
    await waitFor(() => expect(modelApi.deleteModelConfig).toHaveBeenCalledWith('o2'))
  })

  it('can make any imported model the default for a purpose it was not tagged with', async () => {
    const user = userEvent.setup()
    mockLists([channel], [chatModel])
    const visionRow: channelApi.ChannelModel = { ...chatModel, id: 'v1', modelType: 'VLLM', isDefault: false, params: {} }
    vi.mocked(channelApi.addChannelModels).mockResolvedValue([visionRow])
    vi.mocked(modelApi.setDefaultModel).mockResolvedValue(undefined)

    render(<ModelChannelsPanel />)
    const select = await screen.findByRole('combobox', { name: '默认视觉模型' })
    await waitFor(() => expect(select).toBeEnabled())
    await user.click(select)
    // 同名的还有“默认对话模型”里已选中的那一项，这里只点下拉列表里的选项。
    const option = await waitFor(() => {
      const found = screen.getAllByTitle('DeepSeek / deepseek-chat')
        .find((element) => element.classList.contains('ant-select-item-option'))
      if (!found) throw new Error('option not rendered yet')
      return found
    })
    await user.click(option)

    await waitFor(() => expect(channelApi.addChannelModels).toHaveBeenCalledWith('c1', [
      { modelType: 'VLLM', modelId: 'deepseek-chat', modelName: 'DeepSeek / deepseek-chat' },
    ]))
    await waitFor(() => expect(modelApi.setDefaultModel).toHaveBeenCalledWith('v1'))
  })

  it('shows a default that was configured outside any connection', async () => {
    mockLists([channel], [reasonerModel])
    vi.mocked(modelApi.listModelConfigs).mockImplementation(async ({ modelType }) => modelType === 'LLM'
      ? { total: 1, list: [defaultRow('LLM_Legacy', '旧的智谱模型', 'LLM')] }
      : { total: 0, list: [] })

    render(<ModelChannelsPanel />)

    expect(await screen.findByText('旧的智谱模型（单独配置）')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: '默认向量模型' })).toBeDisabled()
  })
})
