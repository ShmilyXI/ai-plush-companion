import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Modal } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as api from '../../api/capabilities'
import type { Capability } from '../../api/capabilities'
import { CapabilityManagementPage } from './CapabilityManagementPage'

vi.mock('../../api/capabilities', async () => {
  const actual = await vi.importActual<typeof import('../../api/capabilities')>('../../api/capabilities')
  return {
    ...actual,
    listCapabilities: vi.fn(), getCapability: vi.fn(), createCapability: vi.fn(), updateCapability: vi.fn(),
    publishCapability: vi.fn(), setCapabilityStatus: vi.fn(), deleteCapability: vi.fn(),
    getCapabilitySecretStatus: vi.fn(), saveCapabilitySecret: vi.fn(), listMcpTools: vi.fn(),
    approveMcpTools: vi.fn(), previewCapabilityRoute: vi.fn(),
    importLocalMcpConfig: vi.fn(),
    createSkillFromPackage: vi.fn(), importSkillPackage: vi.fn(), listSkillPackages: vi.fn(),
    listPluginExecutors: vi.fn(), testMcpConnection: vi.fn(), syncMcpTools: vi.fn(),
  }
})

const common = {
  description: null, status: 'PUBLISHED' as const, draftVersion: 1, publishedVersion: 1,
  executionPrompt: null, semanticThreshold: null, responseMode: null, timeoutMs: null, failureMessage: null,
  triggers: [], tools: [], plugin: null, mcp: null, createdAt: null, updatedAt: null,
}

const plugin: Capability = {
  ...common, id: 'plugin-weather', type: 'PLUGIN', name: '天气插件',
  plugin: { executorName: 'get_weather', inputSchema: {}, configSchema: {}, secretFields: ['api_key'], defaultConfig: {} },
}
const skill: Capability = {
  ...common, id: 'skill-weather', type: 'SKILL', name: '天气查询', executionPrompt: '查询天气',
  semanticThreshold: 0.7, responseMode: 'LLM', timeoutMs: 15000, failureMessage: '失败',
  triggers: [{ type: 'KEYWORD', value: '天气', priority: 10, caseSensitive: false, enabled: true }],
  tools: [{ toolType: 'PLUGIN', toolRefId: 'plugin-weather', toolName: 'get_weather', alias: null,
    purpose: '天气', defaultParams: {}, required: true, sortOrder: 0 }],
}
const mcp: Capability = {
  ...common, id: 'mcp-search', type: 'MCP_SERVER', name: '搜索 MCP',
  mcp: { transport: 'SSE', connectionConfig: { url: 'https://mcp.example/sse' }, secretRefs: {},
    approvedCommandTemplate: null, healthStatus: 'UNKNOWN', lastError: null, lastCheckedAt: null },
}

describe('CapabilityManagementPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(api.listCapabilities).mockResolvedValue({ list: [skill, plugin, mcp], total: 3 })
    vi.mocked(api.getCapability).mockImplementation(async (id) => [skill, plugin, mcp].find((item) => item.id === id)!)
    vi.mocked(api.listMcpTools).mockResolvedValue([])
    vi.mocked(api.listSkillPackages).mockResolvedValue([])
    vi.mocked(api.createSkillFromPackage).mockResolvedValue(skill)
    vi.mocked(api.importSkillPackage).mockResolvedValue({
      capabilityId: 'skill-weather', name: '天气查询', version: 2, packageSha256: 'a'.repeat(64), packageSize: 10,
      manifest: { id: 'skill-weather', tools: [] }, skillMarkdown: '# Weather',
      validation: { status: 'VALID', issues: [] },
    })
    vi.mocked(api.getCapabilitySecretStatus).mockResolvedValue({})
    vi.mocked(api.publishCapability).mockResolvedValue({ ...skill, publishedVersion: 2 })
    vi.mocked(api.importLocalMcpConfig).mockResolvedValue({ imported: ['search'], skipped: [] })
    vi.mocked(api.listPluginExecutors).mockResolvedValue([
      { name: 'get_weather', description: '查询天气', inputSchema: { type: 'object' } },
    ])
    vi.mocked(api.testMcpConnection).mockResolvedValue({ success: true, errorClass: null, tools: [] })
    vi.mocked(api.syncMcpTools).mockResolvedValue({ success: true, errorClass: null, tools: [] })
  })

  it('lists and filters capability types, then opens focused editors', async () => {
    render(<CapabilityManagementPage />)

    expect(await screen.findByText('天气查询')).toBeInTheDocument()
    expect(screen.getByText('天气插件')).toBeInTheDocument()
    expect(screen.getByText('搜索 MCP')).toBeInTheDocument()
    expect(screen.getByLabelText('能力类型')).toBeInTheDocument()
    expect(screen.getByLabelText('状态')).toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: /编\s*辑/ })[0])
    expect(await screen.findByRole('dialog', { name: '编辑 Skill' })).toBeInTheDocument()
    expect(screen.getByLabelText('执行提示词')).toHaveValue('查询天气')
    expect(screen.getByText('天气插件 / get_weather')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /取\s*消/ }))

    await userEvent.click(screen.getByRole('button', { name: /新建能力/ }))
    await userEvent.click(await screen.findByText('新建 Plugin'))
    expect(await screen.findByRole('dialog', { name: '新建 Plugin' })).toBeInTheDocument()
    expect(screen.getByLabelText('执行器标识')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('执行器标识'))
    expect(await screen.findByText('查询天气 / get_weather')).toBeInTheDocument()
  })

  it('shows the capability workflow and the device binding destination', async () => {
    render(<CapabilityManagementPage />)

    await screen.findByText('天气查询')
    expect(screen.getByText('准备工具')).toBeInTheDocument()
    expect(screen.getByText('创建并发布 Skill')).toBeInTheDocument()
    expect(screen.getByText('绑定到设备')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '去设备页绑定' })).toHaveAttribute('href', '/devices')
  })

  it('keeps the capability list usable when the executor catalog fails and retries it in place', async () => {
    vi.mocked(api.listPluginExecutors)
      .mockRejectedValueOnce(new Error('执行器服务暂不可用'))
      .mockResolvedValue([{ name: 'get_weather', description: '查询天气', inputSchema: { type: 'object' } }])
    render(<CapabilityManagementPage />)

    expect(await screen.findByText('天气查询')).toBeInTheDocument()
    expect(await screen.findByText('执行器服务暂不可用')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '重试执行器目录' }))

    await waitFor(() => expect(api.listPluginExecutors).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByText('执行器服务暂不可用')).not.toBeInTheDocument())
    expect(screen.getByText('天气插件')).toBeInTheDocument()
  })

  it('publishes only after confirmation and preserves immutable version context', async () => {
    let confirm: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirm = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<CapabilityManagementPage />)

    await screen.findByText('天气查询')
    await userEvent.click(screen.getAllByRole('button', { name: /发\s*布/ })[0])
    expect(api.publishCapability).not.toHaveBeenCalled()
    await confirm?.()
    expect(api.publishCapability).toHaveBeenCalledWith('skill-weather')
  })

  it('keeps the error visible and retries a failed list request', async () => {
    vi.mocked(api.listCapabilities)
      .mockRejectedValueOnce(new Error('能力服务暂不可用'))
      .mockResolvedValue({ list: [skill], total: 1 })
    render(<CapabilityManagementPage />)

    expect(await screen.findByText('能力服务暂不可用')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(await screen.findByText('天气查询')).toBeInTheDocument()
    await waitFor(() => expect(api.listCapabilities).toHaveBeenCalledTimes(2))
  })

  it('imports a local MCP settings document from the capability center', async () => {
    render(<CapabilityManagementPage />)
    await screen.findByText('天气查询')

    await userEvent.click(screen.getByRole('button', { name: /新建能力/ }))
    await userEvent.click(await screen.findByText('导入本地 MCP JSON'))
    const document = '{"mcpServers":{"search":{"url":"https://mcp.example/sse"}}}'
    fireEvent.change(screen.getByLabelText('MCP 配置 JSON'), { target: { value: document } })
    await userEvent.click(screen.getByRole('button', { name: /导\s+入/ }))

    await waitFor(() => expect(api.importLocalMcpConfig).toHaveBeenCalledWith(JSON.parse(document)))
  })

  it('saves a validated Skill package from the dedicated upload entry', async () => {
    render(<CapabilityManagementPage />)
    await screen.findByText('天气查询')
    await userEvent.click(screen.getByRole('button', { name: '上传 Skill 包' }))
    await userEvent.upload(screen.getByLabelText('Skill 包'), new File(['zip'], 'weather.skill.zip', { type: 'application/zip' }))
    await userEvent.click(await screen.findByRole('button', { name: '保存为草稿' }))

    await waitFor(() => expect(api.createSkillFromPackage).toHaveBeenCalled())
  })

  it('marks undeployed plugins unavailable and excludes them from new Skill tool choices', async () => {
    vi.mocked(api.listPluginExecutors).mockResolvedValue([])
    render(<CapabilityManagementPage />)

    expect(await screen.findByText('天气插件')).toBeInTheDocument()
    expect(await screen.findByText('执行器不可用')).toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: /编\s*辑/ })[0])
    expect(await screen.findByRole('dialog', { name: '编辑 Skill' })).toBeInTheDocument()
    expect(screen.queryByText('天气插件 / get_weather')).not.toBeInTheDocument()
  })

  it('closes the editor after a capability draft is saved', async () => {
    vi.mocked(api.createCapability).mockResolvedValue(skill)
    render(<CapabilityManagementPage />)

    await userEvent.click(await screen.findByRole('button', { name: /新建能力/ }))
    await userEvent.click(await screen.findByText('新建 Plugin'))
    await userEvent.type(screen.getByLabelText('名称'), '天气插件副本')
    await userEvent.click(screen.getByLabelText('执行器标识'))
    await userEvent.click(await screen.findByText('查询天气 / get_weather'))
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }))

    await waitFor(() => expect(api.createCapability).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '新建 Plugin' })).not.toBeInTheDocument())
  })

  it('configures declared Plugin secrets without displaying saved values', async () => {
    vi.mocked(api.getCapabilitySecretStatus).mockResolvedValue({ api_key: false })
    render(<CapabilityManagementPage />)

    await screen.findByText('天气插件')
    await userEvent.click(screen.getAllByRole('button', { name: /编\s*辑/ })[1])
    expect(await screen.findByRole('dialog', { name: '编辑 Plugin' })).toBeInTheDocument()
    expect(api.getCapabilitySecretStatus).toHaveBeenCalledWith('plugin-weather')
    expect(screen.getByText('api_key 未配置')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('api_key 新密钥值'), 'write-only-secret')
    await userEvent.click(screen.getByRole('button', { name: '保存 api_key' }))

    await waitFor(() => expect(api.saveCapabilitySecret)
      .toHaveBeenCalledWith('plugin-weather', 'api_key', 'write-only-secret'))
    expect(screen.queryByDisplayValue('write-only-secret')).not.toBeInTheDocument()
  })

  it('does not expose legacy Skill defaults after a Plugin schema changes', async () => {
    const newsPlugin: Capability = {
      ...plugin,
      id: 'plugin-news',
      name: '新闻插件',
      plugin: {
        executorName: 'get_news_from_newsnow',
        inputSchema: { type: 'object', properties: { source: { type: 'string', description: '新闻源' } } },
        configSchema: {}, secretFields: [], defaultConfig: {},
      },
    }
    const newsSkill: Capability = {
      ...skill,
      id: 'skill-news',
      name: '新闻查询',
      tools: [{ ...skill.tools[0], toolRefId: 'plugin-news', toolName: 'get_news_from_newsnow',
        defaultParams: { category: '', source: '' } }],
    }
    vi.mocked(api.listCapabilities).mockResolvedValue({ list: [newsPlugin, newsSkill], total: 2 })
    vi.mocked(api.getCapability).mockImplementation(async (id) => id === newsSkill.id ? newsSkill : newsPlugin)
    vi.mocked(api.listPluginExecutors).mockResolvedValue([
      { name: 'get_news_from_newsnow', description: '查询新闻', inputSchema: newsPlugin.plugin!.inputSchema },
    ])

    render(<CapabilityManagementPage />)
    await screen.findByText('新闻查询')
    await userEvent.click(screen.getAllByRole('button', { name: /编\s*辑/ })[1])

    expect(await screen.findByLabelText(/get_news_from_newsnow source/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/get_news_from_newsnow category/)).not.toBeInTheDocument()
  })
})
