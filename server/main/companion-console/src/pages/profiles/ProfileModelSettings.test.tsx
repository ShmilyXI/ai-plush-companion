import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { ProfileModelBinding, ProfileModelOption } from '../../api/profiles'
import * as modelApi from '../../api/models'
import { useAuthStore } from '../../auth/authStore'
import { ProfileModelSettings } from './ProfileModelSettings'

const options: ProfileModelOption[] = [
  { id: 'tts-global', modelType: 'TTS', name: '系统语音模型', source: 'global', providerCode: 'edge', enabled: true,
    isDefault: true, vendorName: '微软', protocol: 'Edge TTS', credentialStatus: 'not_required', unavailableReason: null },
  { id: 'tts-private', modelType: 'TTS', name: '我的语音模型', source: 'private', providerCode: 'openai', enabled: true,
    isDefault: false, vendorName: 'OpenAI', protocol: 'OpenAI 兼容', credentialStatus: 'configured', unavailableReason: null },
  { id: 'asr-global', modelType: 'ASR', name: '语音识别', source: 'global', providerCode: 'funasr', enabled: true,
    isDefault: true, vendorName: '阿里云百炼', protocol: 'DashScope', credentialStatus: 'configured', unavailableReason: null },
  { id: 'llm-missing', modelType: 'LLM', name: 'DeepSeek', source: 'global', providerCode: 'openai', enabled: true,
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

  it('offers defaults plus reusable owner-scoped private resources', async () => {
    const change = vi.fn()
    const value: ProfileModelBinding[] = []
    renderSettings(value, change)

    await userEvent.click(screen.getByRole('combobox', { name: '语音合成 TTS' }))
    expect((await screen.findAllByText('跟随系统默认')).length).toBeGreaterThan(0)
    expect(screen.getByText('系统语音模型 · 微软')).toBeInTheDocument()
    expect(screen.queryByText('语音识别 · 阿里云百炼')).not.toBeInTheDocument()
    expect(screen.getByText('我的语音模型 · OpenAI')).toBeInTheDocument()
    await userEvent.click(screen.getByText('系统语音模型 · 微软'))

    expect(change).toHaveBeenCalledWith(expect.arrayContaining([
      expect.objectContaining({ modelType: 'TTS', source: 'global', resourceId: 'tts-global' }),
    ]), expect.objectContaining({ modelType: 'TTS', source: 'global', resourceId: 'tts-global' }))
  })

  it('configures missing global credentials without rendering stored secrets', async () => {
    vi.spyOn(modelApi, 'listModelCatalog').mockResolvedValue([{
      id: 'llm-missing', reference: 'global:llm-missing', modelType: 'LLM', name: 'DeepSeek', providerCode: 'openai',
      vendorCode: 'deepseek', vendorName: 'DeepSeek', protocol: 'OpenAI 兼容', providerTemplateId: 'openai', apiUrl: null,
      modelId: null, credentialRequirement: 'required', credentialConfigured: false, credentialStatus: 'missing', keyUrl: null,
      docsUrl: null, setupGuide: [], credentialFields: [{ key: 'api_key', label: 'API 密钥', type: 'string', required: true, secret: true, options: [], defaultValue: null }],
      unavailableReason: null, source: 'global', enabled: true, defaultModel: false, usageCount: 0, actions: ['configure'],
    }])
    vi.spyOn(modelApi, 'getGlobalModelConfig').mockResolvedValue({ globalModelId: 'llm-missing', apiUrl: null, modelId: null, configuredSecretKeys: [], credentialRequirement: 'required', credentialConfigured: false, credentialStatus: 'missing' })
    const save = vi.spyOn(modelApi, 'saveGlobalModelConfig').mockResolvedValue({ globalModelId: 'llm-missing', apiUrl: null, modelId: null, configuredSecretKeys: ['api_key'], credentialRequirement: 'required', credentialConfigured: true, credentialStatus: 'configured' })
    const changed = vi.fn()
    renderSettings([{ modelType: 'LLM', source: 'global', resourceId: 'llm-missing', overrides: {} }], vi.fn())
    await userEvent.click(screen.getByRole('button', { name: '配置凭据' }))
    expect(await screen.findByRole('dialog', { name: '配置DeepSeek凭据' })).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('API 密钥'), 'secret-value')
    await userEvent.click(screen.getByRole('button', { name: '保存凭据' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith('llm-missing', expect.objectContaining({ secrets: { api_key: 'secret-value' } })))
    expect(screen.queryByText('secret-value')).not.toBeInTheDocument()
    expect(changed).not.toHaveBeenCalled()
  })

  it('only offers enabled models with configured or unnecessary credentials', async () => {
    renderSettings()

    await userEvent.click(screen.getByRole('combobox', { name: '对话模型 LLM' }))
    expect(screen.queryByText('DeepSeek · DeepSeek · 未配置')).not.toBeInTheDocument()
  })

  it('keeps a bound unconfigured model visible only as the disabled current value', async () => {
    renderSettings([
      { modelType: 'LLM', source: 'global', resourceId: 'llm-missing', name: 'DeepSeek',
        overrides: {}, enabled: false, unavailableReason: '请先在模型管理中配置凭据' },
    ])

    expect(screen.getByRole('alert')).toHaveTextContent('请先在模型管理中配置凭据')
    await userEvent.click(screen.getByRole('combobox', { name: '对话模型 LLM' }))
    const dropdown = document.querySelector<HTMLElement>('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
    expect(dropdown).not.toBeNull()
    const current = within(dropdown!).getByText('DeepSeek · DeepSeek · 未配置')
    expect(current.closest('.ant-select-item-option')).toHaveClass('ant-select-item-option-disabled')
    expect(within(dropdown!).getAllByText(/DeepSeek/)).toHaveLength(1)
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
    expect(screen.getByRole('button', { name: '配置凭据' })).toBeVisible()
    expect(screen.queryByText('请联系管理员处理模型配置')).not.toBeInTheDocument()
  })

  it('links super administrators to model management', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'admin',
      user: { id: '1', username: 'admin', superAdmin: 1, status: 1 },
    })
    renderSettings([
      { modelType: 'LLM', source: 'global', resourceId: 'llm-missing', overrides: {} },
    ])

    expect(screen.getByRole('button', { name: '配置凭据' })).toBeVisible()
    expect(screen.queryByRole('link', { name: '前往模型管理' })).not.toBeInTheDocument()
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
