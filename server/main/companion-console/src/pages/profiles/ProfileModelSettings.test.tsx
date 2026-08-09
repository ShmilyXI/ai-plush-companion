import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { ProfileModelBinding, ProfileModelOption } from '../../api/profiles'
import { useAuthStore } from '../../auth/authStore'
import { ProfileModelSettings } from './ProfileModelSettings'

const options: ProfileModelOption[] = [
  { id: 'tts-global', modelType: 'TTS', name: '系统语音模型', source: 'global', providerCode: 'edge', enabled: true,
    isDefault: true, vendorName: '微软', protocol: 'Edge TTS', credentialStatus: 'not_required', unavailableReason: null },
  { id: 'tts-private', modelType: 'TTS', name: '我的语音模型', source: 'private', providerCode: 'openai', enabled: true,
    isDefault: false, vendorName: 'OpenAI', protocol: 'OpenAI 兼容', credentialStatus: 'configured', unavailableReason: null },
  { id: 'asr-global', modelType: 'ASR', name: '语音识别', source: 'global', providerCode: 'funasr', enabled: true,
    isDefault: true, vendorName: '阿里云百炼', protocol: 'DashScope', credentialStatus: 'configured', unavailableReason: null },
  { id: 'llm-missing', modelType: 'LLM', name: 'DeepSeek', source: 'global', providerCode: 'openai', enabled: false,
    isDefault: false, vendorName: 'DeepSeek', protocol: 'OpenAI 兼容', credentialStatus: 'missing', unavailableReason: '请先在模型管理中配置凭据' },
]

function renderSettings(value: ProfileModelBinding[] = [], onChange = vi.fn()) {
  return render(<MemoryRouter><ProfileModelSettings value={value} options={options} onChange={onChange} /></MemoryRouter>)
}

describe('ProfileModelSettings', () => {
  afterEach(() => useAuthStore.getState().clearSession())
  it('explains every model type with a visible Chinese purpose', () => {
    renderSettings()

    expect(screen.getByText('对话模型 LLM')).toBeVisible()
    expect(screen.getByText('负责理解问题并生成回复')).toBeVisible()
    expect(screen.getByText('语音识别 ASR')).toBeVisible()
    expect(screen.getByText('把用户说的话转换成文字')).toBeVisible()
    expect(screen.getByText('语音合成 TTS')).toBeVisible()
    expect(screen.getByText('把机器人回复转换成声音')).toBeVisible()
    expect(screen.getByText('声音活动检测 VAD')).toBeVisible()
    expect(screen.getByText('判断用户何时开始和结束说话')).toBeVisible()
    expect(screen.getByText('视觉理解 VLLM')).toBeVisible()
    expect(screen.getByText('理解摄像头拍到的图片内容')).toBeVisible()
    expect(screen.getByText('长期记忆 Memory')).toBeVisible()
    expect(screen.getByText('保存并检索长期对话记忆')).toBeVisible()
  })

  it('offers only defaults and global native resources', async () => {
    const change = vi.fn()
    const value: ProfileModelBinding[] = []
    renderSettings(value, change)

    await userEvent.click(screen.getByRole('combobox', { name: '语音合成 TTS' }))
    expect((await screen.findAllByText('跟随系统默认')).length).toBeGreaterThan(0)
    expect(screen.getByText('系统语音模型 · 微软')).toBeInTheDocument()
    expect(screen.queryByText('语音识别 · 阿里云百炼')).not.toBeInTheDocument()
    expect(screen.queryByText('我的语音模型 · OpenAI')).not.toBeInTheDocument()
    expect(screen.queryByText('个人模型')).not.toBeInTheDocument()
    await userEvent.click(screen.getByText('系统语音模型 · 微软'))

    expect(change).toHaveBeenCalledWith(expect.arrayContaining([
      expect.objectContaining({ modelType: 'TTS', source: 'global', resourceId: 'tts-global' }),
    ]), expect.objectContaining({ modelType: 'TTS', source: 'global', resourceId: 'tts-global' }))
  })

  it('keeps missing-credential models visible but disabled', async () => {
    renderSettings()

    await userEvent.click(screen.getByRole('combobox', { name: '对话模型 LLM' }))
    const missing = screen.getByText('DeepSeek · DeepSeek · 未配置')
    expect(missing).toBeInTheDocument()
    expect(missing.closest('.ant-select-item-option')).toHaveClass('ant-select-item-option-disabled')
  })

  it('shows plain administrator guidance to ordinary users', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'normal',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })
    renderSettings([
      { modelType: 'LLM', source: 'global', resourceId: 'llm-missing', overrides: {} },
    ])

    expect(screen.getByRole('alert')).toHaveTextContent('请先在模型管理中配置凭据')
    expect(screen.getByText('请联系管理员处理模型配置')).toBeVisible()
    expect(screen.queryByRole('link', { name: '前往模型管理' })).not.toBeInTheDocument()
  })

  it('does not link administrators to the ordinary model route', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'admin',
      user: { id: '1', username: 'admin', superAdmin: 1, status: 1 },
    })
    renderSettings([
      { modelType: 'LLM', source: 'global', resourceId: 'llm-missing', overrides: {} },
    ])

    expect(screen.queryByRole('link', { name: '前往模型管理' })).not.toBeInTheDocument()
    expect(screen.getByText('请联系管理员处理模型配置')).toBeVisible()
  })

  it('shows the server migration name and reason for a retired private binding', async () => {
    const change = vi.fn()
    renderSettings([
      { modelType: 'LLM', source: 'private', resourceId: 'private-old', name: '旧个人模型',
        overrides: {}, enabled: false, unavailableReason: '旧个人模型已停用，请重新选择' },
    ], change)

    expect(screen.getByRole('alert')).toHaveTextContent('旧个人模型已停用，请重新选择')
    await userEvent.click(screen.getByRole('combobox', { name: '对话模型 LLM' }))
    expect(screen.getAllByText('旧个人模型').length).toBeGreaterThan(0)
    expect(screen.queryByText('个人模型')).not.toBeInTheDocument()
    await userEvent.click(screen.getAllByText('跟随系统默认').at(-1)!)
    expect(change).toHaveBeenCalledWith(expect.anything(), { modelType: 'LLM', source: 'default' })
  })

  it('does not expose structured or arbitrary override editors', () => {
    renderSettings([{ modelType: 'TTS', source: 'global', resourceId: 'tts-global', overrides: { model: 'legacy' } }])

    expect(screen.queryByLabelText('语音合成 TTS模型 ID 覆盖')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('TTS 语速覆盖')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('语音合成 TTS高级参数')).not.toBeInTheDocument()
  })
})
