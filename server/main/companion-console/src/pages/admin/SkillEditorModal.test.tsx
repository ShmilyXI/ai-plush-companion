import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { Capability } from '../../api/capabilities'
import { SkillEditorModal } from './SkillEditorModal'

const capability: Capability = {
  id: 'skill-weather', type: 'SKILL', name: '天气查询', description: '查询天气', status: 'PUBLISHED',
  draftVersion: 2, publishedVersion: 1, executionPrompt: '旧提示词', semanticThreshold: 0.7,
  responseMode: 'LLM', timeoutMs: 15000, failureMessage: '查询失败',
  triggers: [
    { type: 'KEYWORD', value: '天气', priority: 10, caseSensitive: false, enabled: true },
    { type: 'POSITIVE_EXAMPLE', value: '上海今天冷吗', priority: 0, caseSensitive: false, enabled: true },
    { type: 'NEGATIVE_EXAMPLE', value: '聊聊天气这个话题', priority: 0, caseSensitive: false, enabled: true },
  ],
  tools: [{ toolType: 'PLUGIN', toolRefId: 'plugin-weather', toolName: 'get_weather', alias: null,
    purpose: '天气查询', defaultParams: {}, required: true, sortOrder: 0 }],
  plugin: null, mcp: null, createdAt: null, updatedAt: null,
}

describe('SkillEditorModal', () => {
  it('edits prompts and mixed triggers while selecting only catalog tools', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    render(<SkillEditorModal open capability={capability} saving={false} error="" onCancel={vi.fn()}
      onSave={onSave} toolOptions={[{
        key: 'PLUGIN:plugin-weather:get_weather', label: '天气插件 / get_weather',
        tool: capability.tools[0],
      }]} />)

    expect(screen.getByDisplayValue('天气')).toBeInTheDocument()
    expect(screen.getByDisplayValue('上海今天冷吗')).toBeInTheDocument()
    expect(screen.getByDisplayValue('聊聊天气这个话题')).toBeInTheDocument()
    expect(screen.getByText('天气插件 / get_weather')).toBeInTheDocument()
    expect(screen.queryByText(/上传代码|脚本|Python|JavaScript/)).not.toBeInTheDocument()

    const prompt = screen.getByLabelText('执行提示词')
    await userEvent.clear(prompt)
    await userEvent.type(prompt, '根据工具结果回答天气。')
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }))

    await waitFor(() => expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      type: 'SKILL', name: '天气查询', executionPrompt: '根据工具结果回答天气。',
      triggers: expect.arrayContaining([
        expect.objectContaining({ type: 'KEYWORD', value: '天气' }),
        expect.objectContaining({ type: 'POSITIVE_EXAMPLE', value: '上海今天冷吗' }),
        expect.objectContaining({ type: 'NEGATIVE_EXAMPLE', value: '聊聊天气这个话题' }),
      ]),
      tools: [expect.objectContaining({ toolRefId: 'plugin-weather', toolName: 'get_weather' })],
    })))
  })

  it('shows immutable published version context without making it editable', () => {
    render(<SkillEditorModal open capability={capability} saving={false} error="" onCancel={vi.fn()}
      onSave={vi.fn()} toolOptions={[]} />)

    expect(screen.getByText('已发布版本 v1')).toBeInTheDocument()
    expect(screen.getByText('编辑后需重新发布，新版本不会覆盖 v1。')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '包设置' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '执行说明' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '清单预览' })).toBeInTheDocument()
  })

  it('allows deleting only draft distribution packages', async () => {
    const onDeletePackage = vi.fn().mockResolvedValue(undefined)
    render(<SkillEditorModal open capability={capability} saving={false} error="" onCancel={vi.fn()}
      onSave={vi.fn()} onDeletePackage={onDeletePackage} toolOptions={[]} packageVersions={[
        { id: 'package-2', capabilityId: capability.id, version: 2, packageSha256: 'a'.repeat(64),
          packageSize: 100, source: 'ONLINE', validationStatus: 'VALID', validationIssues: [],
          published: false, createdAt: null, publishedAt: null },
        { id: 'package-1', capabilityId: capability.id, version: 1, packageSha256: 'b'.repeat(64),
          packageSize: 100, source: 'ONLINE', validationStatus: 'VALID', validationIssues: [],
          published: true, createdAt: null, publishedAt: null },
      ]} />)

    const deleteButtons = screen.getAllByRole('button', { name: '删除草稿' })
    expect(deleteButtons[0]).toBeEnabled()
    expect(deleteButtons[1]).toBeDisabled()
    await userEvent.click(deleteButtons[0])

    expect(onDeletePackage).toHaveBeenCalledWith(capability.id, 2)
  })

  it('previews device routing without executing tools', async () => {
    const onPreview = vi.fn().mockResolvedValue({
      deterministicMatches: ['skill-weather'], semanticRequired: false,
      eligibleSkillIds: ['skill-weather'], selectedSkillId: 'skill-weather',
      allowedTools: ['get_weather'],
    })
    render(<SkillEditorModal open capability={capability} saving={false} error="" onCancel={vi.fn()}
      onSave={vi.fn()} onPreview={onPreview} toolOptions={[]} />)

    await userEvent.type(screen.getByLabelText('设备 ID'), 'device-1')
    await userEvent.type(screen.getByLabelText('用户话术'), '上海今天天气怎么样')
    await userEvent.click(screen.getByRole('button', { name: /预\s*览路由/ }))

    await waitFor(() => expect(onPreview).toHaveBeenCalledWith('device-1', '上海今天天气怎么样'))
    expect(await screen.findByText('规则命中 skill-weather')).toBeInTheDocument()
    expect(screen.getByText('最终 Skill skill-weather')).toBeInTheDocument()
    expect(screen.getByText('允许工具 get_weather')).toBeInTheDocument()
    expect(screen.getByText('仅预览路由，不会执行工具。')).toBeInTheDocument()
  })

  it('edits defaults for the tools selected by a Skill', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    const newsTool = {
      toolType: 'PLUGIN' as const, toolRefId: 'plugin-news', toolName: 'get_news_from_newsnow',
      alias: null, purpose: '新闻查询', defaultParams: {}, required: true, sortOrder: 0,
    }
    const newsSkill = { ...capability, id: 'skill-news', name: '科技快报', tools: [newsTool] }
    render(<SkillEditorModal open capability={newsSkill} saving={false} error="" onCancel={vi.fn()}
      onSave={onSave} toolOptions={[{
        key: 'PLUGIN:plugin-news:get_news_from_newsnow', label: '新闻查询 / get_news_from_newsnow',
        tool: newsTool,
        parameters: [
          { name: 'source', label: '新闻源', type: 'string' },
          { name: 'detail', label: '获取详情', type: 'boolean' },
        ],
      }]} />)

    await userEvent.type(screen.getByLabelText('新闻查询 / get_news_from_newsnow source'), 'IT之家')
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }))

    await waitFor(() => expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      tools: [expect.objectContaining({ defaultParams: expect.objectContaining({ source: 'IT之家' }) })],
    })))
  })

  it('loads role MCP tools into the same allowlist used by package editing', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    render(<SkillEditorModal open capability={capability} saving={false} error="" onCancel={vi.fn()}
      onSave={onSave} onLoadRoleMcpTools={vi.fn().mockResolvedValue([{
        toolType: 'ROLE_MCP', toolRefId: 'agent-weather', toolName: 'get_weather', alias: null,
        purpose: '天气', defaultParams: {}, required: true, sortOrder: 0,
      }])} toolOptions={[]} />)

    await userEvent.type(screen.getByLabelText('角色 ID'), 'agent-weather')
    await userEvent.click(screen.getByRole('button', { name: '加载角色 MCP 工具' }))
    await userEvent.click(screen.getByLabelText('从已登记工具中选择'))
    await userEvent.click(await screen.findByText('角色 MCP agent-weather / get_weather'))
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }))

    await waitFor(() => expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      tools: [expect.objectContaining({ toolType: 'ROLE_MCP', toolRefId: 'agent-weather', toolName: 'get_weather' })],
    })))
  })

  it('shows a role MCP catalog error without closing the editor', async () => {
    render(<SkillEditorModal open capability={capability} saving={false} error="" onCancel={vi.fn()}
      onSave={vi.fn()} onLoadRoleMcpTools={vi.fn().mockRejectedValue(new Error('角色接入点离线'))}
      toolOptions={[]} />)

    await userEvent.type(screen.getByLabelText('角色 ID'), 'agent-weather')
    await userEvent.click(screen.getByRole('button', { name: '加载角色 MCP 工具' }))

    expect(await screen.findByText('角色接入点离线')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存草稿' })).toBeInTheDocument()
  })

  it('drops legacy defaults that are absent from the current tool schema', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    const newsTool = {
      toolType: 'PLUGIN' as const, toolRefId: 'plugin-news', toolName: 'get_news_from_newsnow',
      alias: null, purpose: '新闻查询', defaultParams: { category: '', source: '' }, required: true, sortOrder: 0,
    }
    const newsSkill = { ...capability, id: 'skill-news', name: '新闻查询', tools: [newsTool] }
    render(<SkillEditorModal open capability={newsSkill} saving={false} error="" onCancel={vi.fn()}
      onSave={onSave} toolOptions={[{
        key: 'PLUGIN:plugin-news:get_news_from_newsnow', label: '新闻查询 / get_news_from_newsnow',
        tool: newsTool,
        parameters: [{ name: 'source', label: '新闻源', type: 'string' }],
      }]} />)

    expect(screen.queryByLabelText('新闻查询 / get_news_from_newsnow category')).not.toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('新闻查询 / get_news_from_newsnow source'), 'IT之家')
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }))

    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const saved = onSave.mock.calls[0][0]
    expect(saved.tools[0].defaultParams).toEqual({ source: 'IT之家' })
  })
})
