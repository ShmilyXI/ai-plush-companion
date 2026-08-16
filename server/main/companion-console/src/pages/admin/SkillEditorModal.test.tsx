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
})
