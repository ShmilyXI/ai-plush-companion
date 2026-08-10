import '@ant-design/v5-patch-for-react-19'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider, useNavigate } from 'react-router-dom'
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'

import http from '../../api/http'
import * as profileApi from '../../api/profiles'
import * as modelApi from '../../api/xiaozhiModels'
import { ProfileEditorPage } from './ProfileEditorPage'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

let audioSrcAttribute: string | null = null
const audio = {
  pause: vi.fn(),
  play: vi.fn<() => Promise<void>>(),
  removeAttribute: vi.fn(),
  hasAttribute: vi.fn(),
  load: vi.fn(),
  currentTime: 0,
  get src() {
    if (audioSrcAttribute === null) return ''
    return audioSrcAttribute === '' ? window.location.href : audioSrcAttribute
  },
  set src(value: string) { audioSrcAttribute = value },
  onerror: null as null | (() => void),
}

const profile: profileApi.CompanionProfile = {
  id: 'profile-a',
  name: '小满',
  relationMode: 'friend',
  userAddress: '小夏',
  personality: '温柔、有耐心',
  systemPrompt: '旧提示词',
  companionCues: { laugh: true, sigh: false, hesitate: true, breathe: false },
  screenExpressionEnabled: true,
  cameraPreferenceEnabled: false,
  templateId: 'template-a',
  llmModelId: 'llm-a',
  llmModelName: '陪伴模型',
  ttsModelId: 'tts-a',
  ttsModelName: '自然语音',
  ttsVoiceId: 'voice-a',
  ttsVoiceName: '晴岚',
  ttsLanguage: 'zh-CN',
  createdAt: '2026-07-20T10:00:00Z',
  updatedAt: '2026-07-29T10:00:00Z',
  models: [{ modelType: 'TTS', source: 'global', resourceId: 'tts-a' }],
  effectiveModels: [],
}

const NativeRequest = globalThis.Request

class RouterTestRequest {
  readonly url: string
  readonly method: string
  readonly signal: AbortSignal
  readonly headers: Headers

  constructor(input: string | URL | { url: string }, init: RequestInit = {}) {
    this.url = typeof input === 'string' || input instanceof URL ? input.toString() : input.url
    this.method = init.method ?? 'GET'
    this.signal = init.signal ?? new AbortController().signal
    this.headers = new Headers(init.headers)
  }
}

const legacyEditableModels: profileApi.ProfileModelBinding[] = [
  { modelType: 'LLM', source: 'global', resourceId: 'llm-a', name: '陪伴模型', enabled: true },
  { modelType: 'ASR', source: 'global', resourceId: 'asr-a', name: '识别模型', enabled: true },
  { modelType: 'TTS', source: 'global', resourceId: 'tts-a', name: '自然语音', enabled: true },
  { modelType: 'VAD', source: 'global', resourceId: 'vad-a', name: '检测模型', enabled: true },
  { modelType: 'VLLM', source: 'global', resourceId: 'vllm-a', name: '视觉模型', enabled: true },
  { modelType: 'Memory', source: 'global', resourceId: 'memory-a', name: '记忆模型', enabled: true },
]

function renderPage(id = 'profile-a') {
  const router = createMemoryRouter([{ path: '/profiles/:id', element: <ProfileEditorPage /> }], {
    initialEntries: [`/profiles/${encodeURIComponent(id)}`],
  })
  return render(<RouterProvider router={router} />)
}

function ProfileRouteControls() {
  const navigate = useNavigate()
  return <button onClick={() => navigate('/profiles/profile-b')}>切换角色</button>
}

function renderPageWithRouteControls() {
  const router = createMemoryRouter([{ path: '/profiles/:id', element: <><ProfileRouteControls /><ProfileEditorPage /></> }], {
    initialEntries: ['/profiles/profile-a'],
  })
  return render(<RouterProvider router={router} />)
}

function renderEditorRouter(initialEntry = '/profiles/profile-a') {
  const router = createMemoryRouter([
    { path: '/profiles/:id', element: <ProfileEditorPage /> },
    { path: '/profiles', element: <div>角色列表页</div> },
  ], { initialEntries: [initialEntry] })
  return { router, ...render(<RouterProvider router={router} />) }
}

async function openTab(user: ReturnType<typeof userEvent.setup>, name: '角色设定' | 'AI 模型' | '声音与情绪' | '设备能力' | '版本记录') {
  const tab = await screen.findByRole('tab', { name })
  if (tab.getAttribute('aria-selected') !== 'true') await user.click(tab)
}

describe('profile API adapter', () => {
  it('rejects malformed profile payloads at runtime', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: { ...profile, relationMode: 'assistant' } } })
    await expect(profileApi.getProfile('profile-a')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it('encodes profile ids as one path segment', async () => {
    const rawId = 'profile /?#%'
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data: null } })
    await profileApi.restorePrompt(rawId)
    expect(http.post).toHaveBeenCalledWith(`/companion/profiles/${encodeURIComponent(rawId)}/restore-prompt`, undefined, undefined)
  })

  it('reads the ordinary template catalog without accepting malformed fields', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: [
      { id: 'template-a', code: 'companion-a', name: '治愈伙伴', relationMode: 'friend', cues: ['laugh'] },
    ] } })
    await expect(profileApi.listTemplates()).resolves.toHaveLength(1)
    vi.mocked(http.get).mockResolvedValueOnce({ data: { code: 0, msg: 'success', data: [
      { id: 'template-a', code: 'companion-a', name: '治愈伙伴', relationMode: 'friend', cues: ['private-cue'] },
    ] } })
    await expect(profileApi.listTemplates()).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it('encodes model ids and accepts a null voice list', async () => {
    const rawId = 'tts /?#%'
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: null } })
    await expect(profileApi.listVoices(rawId)).resolves.toEqual([])
    expect(http.get).toHaveBeenCalledWith(`/models/${encodeURIComponent(rawId)}/voices`, undefined)
  })

  it('passes the snapshot version anchor as a query parameter', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: { total: 0, list: [] } } })

    await profileApi.listProfileVersions('profile-a', 2, 10, 20)

    expect(http.get).toHaveBeenCalledWith('/agent/profile-a/snapshots', {
      params: { page: 2, limit: 10, maxVersionNo: 20 },
    })
  })

  it('parses profile model options from the ordinary-user route', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: [
      { id: 'llm-private', modelType: 'LLM', name: '我的大模型', source: 'private', providerCode: 'openai', enabled: true,
        isDefault: true, vendorName: 'OpenAI', protocol: 'OpenAI 兼容', credentialStatus: 'configured', unavailableReason: null },
    ] }, config: {} })

    await expect(profileApi.listProfileModelOptions('profile/a')).resolves.toEqual([
      { id: 'llm-private', modelType: 'LLM', name: '我的大模型', source: 'private', providerCode: 'openai', enabled: true,
        isDefault: true, vendorName: 'OpenAI', protocol: 'OpenAI 兼容', credentialStatus: 'configured', unavailableReason: null },
    ])
    expect(http.get).toHaveBeenCalledWith('/companion/profiles/profile%2Fa/model-options', undefined)
  })

  it('rejects ambiguous enabled default model metadata', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: [
      { id: 'tts-a', modelType: 'TTS', name: '默认语音 A', source: 'global', providerCode: 'edge', enabled: true,
        isDefault: true, vendorName: '微软', protocol: 'Edge TTS', credentialStatus: 'not_required', unavailableReason: null },
      { id: 'tts-b', modelType: 'TTS', name: '默认语音 B', source: 'global', providerCode: 'edge', enabled: true,
        isDefault: true, vendorName: '微软', protocol: 'Edge TTS', credentialStatus: 'not_required', unavailableReason: null },
    ] }, config: {} })

    await expect(profileApi.listProfileModelOptions('profile-a')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })
})

describe('ProfileEditorPage', () => {
  afterAll(() => {
    vi.stubGlobal('Request', NativeRequest)
  })

  beforeEach(() => {
    vi.stubGlobal('Request', RouterTestRequest)
    vi.spyOn(profileApi, 'getProfile').mockResolvedValue(profile)
    vi.spyOn(profileApi, 'listProfileVersions').mockResolvedValue({ total: 1, list: [
      { id: 'snapshot-2', versionNo: 2, source: 'companion-update', createdAt: '2026-07-29T10:00:00Z' },
    ] })
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: 'https://example.com/qinglan.mp3', isClone: false },
    ])
    vi.spyOn(profileApi, 'listProfileModelOptions').mockResolvedValue([
      { id: 'tts-a', modelType: 'TTS', name: '自然语音', source: 'global', providerCode: 'edge', enabled: true,
        isDefault: true, vendorName: '微软', protocol: 'Edge TTS', credentialStatus: 'not_required', unavailableReason: null },
      { id: 'tts-private', modelType: 'TTS', name: '我的语音模型', source: 'private', providerCode: 'custom', enabled: true,
        isDefault: false, vendorName: '自定义', protocol: 'OpenAI 兼容', credentialStatus: 'configured', unavailableReason: null },
      { id: 'tts-b', modelType: 'TTS', name: '备用语音', source: 'global', providerCode: 'edge', enabled: true,
        isDefault: false, vendorName: '微软', protocol: 'Edge TTS', credentialStatus: 'not_required', unavailableReason: null },
    ])
    audio.pause.mockReset()
    audio.play.mockReset().mockResolvedValue(undefined)
    audio.removeAttribute.mockReset().mockImplementation((name: string) => { if (name === 'src') audioSrcAttribute = null })
    audio.hasAttribute.mockReset().mockImplementation((name: string) => name === 'src' && audioSrcAttribute !== null)
    audio.load.mockReset()
    audio.currentTime = 0
    audioSrcAttribute = null
    audio.onerror = null
    vi.stubGlobal('Audio', vi.fn(() => audio))
  })

  it('opens the tab from the URL and preserves the shared draft when switching tabs', async () => {
    const { router } = renderEditorRouter('/profiles/profile-a?tab=models&source=campaign')
    const user = userEvent.setup()

    expect(await screen.findByRole('tab', { name: 'AI 模型' })).toHaveAttribute('aria-selected', 'true')
    await user.click(screen.getByRole('tab', { name: '角色设定' }))
    const name = await screen.findByLabelText('角色名称')
    await user.clear(name)
    await user.type(name, '本地草稿')
    await user.click(screen.getByRole('tab', { name: 'AI 模型' }))
    await user.click(screen.getByRole('tab', { name: '角色设定' }))

    expect(screen.getByDisplayValue('本地草稿')).toBeVisible()
    expect(router.state.location.search).toBe('?tab=basics&source=campaign')
    expect(screen.queryByText('离开角色编辑？')).not.toBeInTheDocument()
  })

  it('guards dirty router navigation and supports continuing or discarding edits', async () => {
    const { router } = renderEditorRouter()
    const user = userEvent.setup()
    const name = await screen.findByLabelText('角色名称')
    await user.clear(name)
    await user.type(name, '未保存名称')

    await act(async () => { await router.navigate('/profiles') })
    expect(await screen.findByText('离开角色编辑？')).toBeInTheDocument()
    expect(screen.getByText('尚有未保存的修改')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '继续编辑', hidden: true }))
    expect(router.state.location.pathname).toBe('/profiles/profile-a')
    expect(screen.getByDisplayValue('未保存名称')).toBeVisible()
    await waitFor(() => expect(screen.queryByText('离开角色编辑？')).not.toBeInTheDocument())

    await act(async () => { await router.navigate('/profiles') })
    fireEvent.click(await screen.findByRole('button', { name: '放弃修改', hidden: true }))
    expect(await screen.findByText('角色列表页')).toBeVisible()
  })

  it('prevents beforeunload only while the shared draft is dirty', async () => {
    renderEditorRouter()
    const cleanEvent = new Event('beforeunload', { cancelable: true })
    const cleanPreventDefault = vi.spyOn(cleanEvent, 'preventDefault')
    window.dispatchEvent(cleanEvent)
    expect(cleanPreventDefault).not.toHaveBeenCalled()

    const user = userEvent.setup()
    const name = await screen.findByLabelText('角色名称')
    await user.clear(name)
    await user.type(name, '未保存名称')
    const dirtyEvent = new Event('beforeunload', { cancelable: true })
    const dirtyPreventDefault = vi.spyOn(dirtyEvent, 'preventDefault')
    window.dispatchEvent(dirtyEvent)

    expect(dirtyPreventDefault).toHaveBeenCalledOnce()
  })

  it('uses product relationship language and exposes only the cue whitelist', async () => {
    renderPage()
    const user = userEvent.setup()
    expect(await screen.findByDisplayValue('小满')).toBeVisible()
    expect(screen.getByText('治愈型朋友')).toBeVisible()
    expect(screen.getByText('治愈型恋人')).toBeVisible()
    await openTab(user, '声音与情绪')
    expect(screen.getByText('开心轻笑')).toBeVisible()
    expect(screen.getByText('轻轻叹息')).toBeVisible()
    expect(screen.getByText('犹豫停顿')).toBeVisible()
    expect(screen.getByText('安定呼吸')).toBeVisible()
    expect(screen.queryByText(/config\/assets|\.wav/)).not.toBeInTheDocument()
  })

  it('validates required fields before saving', async () => {
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()
    const name = await screen.findByLabelText('角色名称')
    await user.clear(name)
    await user.click(screen.getByRole('button', { name: '保存角色' }))
    expect(await screen.findByText('请输入角色名称')).toBeVisible()
    expect(update).not.toHaveBeenCalled()
  })

  it('does not restore the prompt when confirmation is cancelled', async () => {
    const restore = vi.spyOn(profileApi, 'restorePrompt').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '恢复初始提示词' }))
    expect(within(await screen.findByRole('dialog')).getByText('只恢复完整提示词，不会覆盖名称、关系、声音、设备绑定和历史记录。')).toBeInTheDocument()
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: /取\s*消/ }))
    expect(restore).not.toHaveBeenCalled()
  })

  it('restores the prompt only after confirmation and reloads server state', async () => {
    const restore = vi.spyOn(profileApi, 'restorePrompt').mockResolvedValue(undefined)
    vi.spyOn(profileApi, 'getProfile')
      .mockResolvedValueOnce(profile)
      .mockResolvedValueOnce({ ...profile, systemPrompt: '初始提示词' })
    renderPage()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '恢复初始提示词' }))
    await user.click(screen.getByRole('button', { name: '确认恢复' }))
    await waitFor(() => expect(restore).toHaveBeenCalledWith('profile-a', expect.objectContaining({ signal: expect.any(AbortSignal) })))
    expect(await screen.findByDisplayValue('初始提示词')).toBeVisible()
  })

  it('keeps unsaved non-prompt fields when restoring the prompt', async () => {
    const restore = vi.spyOn(profileApi, 'restorePrompt').mockResolvedValue(undefined)
    vi.spyOn(profileApi, 'getProfile')
      .mockResolvedValueOnce(profile)
      .mockResolvedValueOnce({ ...profile, name: '服务端旧名称', personality: '服务端旧性格', systemPrompt: '初始提示词' })
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()
    const name = await screen.findByLabelText('角色名称')
    const personality = screen.getByLabelText('性格')
    await user.clear(name)
    await user.type(name, '本地未保存名称')
    await user.clear(personality)
    await user.type(personality, '本地未保存性格')

    await user.click(screen.getByRole('button', { name: '恢复初始提示词' }))
    await user.click(screen.getByRole('button', { name: '确认恢复' }))

    expect(await screen.findByDisplayValue('初始提示词')).toBeVisible()
    expect(screen.getByDisplayValue('本地未保存名称')).toBeVisible()
    expect(screen.getByDisplayValue('本地未保存性格')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('profile-a', expect.objectContaining({
      name: '本地未保存名称', personality: '本地未保存性格', systemPrompt: '初始提示词',
    }), expect.anything()))
    expect(restore).toHaveBeenCalledOnce()
  })

  it('preserves a prompt edited while restore is pending', async () => {
    const pendingRestore = deferred<void>()
    vi.spyOn(profileApi, 'restorePrompt').mockReturnValue(pendingRestore.promise)
    vi.spyOn(profileApi, 'getProfile')
      .mockResolvedValueOnce(profile)
      .mockResolvedValueOnce({ ...profile, systemPrompt: '服务端初始提示词' })
    const { router } = renderEditorRouter()
    const user = userEvent.setup()

    const prompt = await screen.findByLabelText('完整提示词')
    await user.click(screen.getByRole('button', { name: '恢复初始提示词' }))
    await user.click(screen.getByRole('button', { name: '确认恢复' }))
    await waitFor(() => expect(profileApi.restorePrompt).toHaveBeenCalled())
    await user.clear(prompt)
    await user.type(prompt, '恢复期间的新提示词')
    await act(async () => {
      pendingRestore.resolve()
      await pendingRestore.promise
    })

    expect(await screen.findByDisplayValue('恢复期间的新提示词')).toBeVisible()
    await act(async () => { await router.navigate('/profiles') })
    expect(await screen.findByText('离开角色编辑？')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '继续编辑', hidden: true }))
    await waitFor(() => expect(screen.queryByText('离开角色编辑？')).not.toBeInTheDocument())
  })

  it('applies an uncontested restored prompt as a dirty savable draft', async () => {
    vi.spyOn(profileApi, 'restorePrompt').mockResolvedValue(undefined)
    vi.spyOn(profileApi, 'getProfile')
      .mockResolvedValueOnce(profile)
      .mockResolvedValueOnce({ ...profile, systemPrompt: '服务端初始提示词' })
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    const { router } = renderEditorRouter()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '恢复初始提示词' }))
    await user.click(screen.getByRole('button', { name: '确认恢复' }))
    expect(await screen.findByDisplayValue('服务端初始提示词')).toBeVisible()
    await act(async () => { await router.navigate('/profiles') })
    expect(await screen.findByText('离开角色编辑？')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '继续编辑', hidden: true }))
    await waitFor(() => expect(screen.queryByText('离开角色编辑？')).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('profile-a', expect.objectContaining({ systemPrompt: '服务端初始提示词' }), expect.anything()))
  })

  it('blocks restore actions while a save is pending and unlocks them after completion', async () => {
    const pendingSave = deferred<void>()
    const update = vi.spyOn(profileApi, 'updateProfile').mockReturnValue(pendingSave.promise)
    const restore = vi.spyOn(profileApi, 'restorePrompt').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '恢复初始提示词' }))
    await user.click(screen.getByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalled())

    const confirm = screen.getByRole('button', { name: '确认恢复' })
    expect(confirm).toBeDisabled()
    await user.click(confirm)
    expect(restore).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: /取\s*消/ }))
    const restoreEntry = screen.getByRole('button', { name: '恢复初始提示词' })
    expect(restoreEntry).toBeDisabled()

    await act(async () => {
      pendingSave.resolve()
      await pendingSave.promise
    })
    await waitFor(() => expect(restoreEntry).toBeEnabled())
    await user.click(restoreEntry)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('preserves edits made after a save starts and submits them in the next save', async () => {
    const pendingSave = deferred<void>()
    vi.spyOn(modelApi, 'listModelVoices')
      .mockResolvedValueOnce([{ id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: null, isClone: false }])
      .mockResolvedValueOnce([{ id: 'voice-b', name: '晚风', languages: '中文', voiceDemo: null, isClone: false }])
    const update = vi.spyOn(profileApi, 'updateProfile')
      .mockReturnValueOnce(pendingSave.promise)
      .mockResolvedValueOnce(undefined)
    const { router } = renderEditorRouter()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalledOnce())
    const name = screen.getByLabelText('角色名称')
    await user.clear(name)
    await user.type(name, '请求后的新名称')
    await openTab(user, 'AI 模型')
    await user.click(screen.getByRole('combobox', { name: '语音合成 TTS' }))
    await user.click(await screen.findByText('备用语音 · 微软'))
    await openTab(user, '声音与情绪')
    await user.click(screen.getByRole('combobox', { name: '声音' }))
    await user.click(await screen.findByText('晚风'))

    await act(async () => {
      pendingSave.resolve()
      await pendingSave.promise
    })
    await openTab(user, '角色设定')
    expect(screen.getByDisplayValue('请求后的新名称')).toBeVisible()
    await act(async () => { await router.navigate('/profiles') })
    expect(await screen.findByText('离开角色编辑？')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '继续编辑', hidden: true }))
    await waitFor(() => expect(screen.queryByText('离开角色编辑？')).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalledTimes(2))
    expect(update.mock.calls[1][1]).toEqual(expect.objectContaining({
      name: '请求后的新名称',
      ttsVoiceId: 'voice-b',
      models: expect.arrayContaining([expect.objectContaining({ modelType: 'TTS', resourceId: 'tts-b' })]),
    }))
  })

  it('does not restore the old voice when only TTS changes during a save', async () => {
    const pendingSave = deferred<void>()
    vi.spyOn(modelApi, 'listModelVoices')
      .mockResolvedValueOnce([{ id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: null, isClone: false }])
      .mockResolvedValueOnce([{ id: 'voice-b', name: '晚风', languages: '中文', voiceDemo: null, isClone: false }])
    const update = vi.spyOn(profileApi, 'updateProfile').mockReturnValue(pendingSave.promise)
    const { router } = renderEditorRouter()
    const user = userEvent.setup()
    const voiceInput = await screen.findByRole('combobox', { name: '声音', hidden: true })

    await user.click(await screen.findByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalledOnce())
    await openTab(user, 'AI 模型')
    await user.click(screen.getByRole('combobox', { name: '语音合成 TTS' }))
    await user.click(await screen.findByText('备用语音 · 微软'))
    await act(async () => {
      pendingSave.resolve()
      await pendingSave.promise
    })
    await screen.findByText('角色设置已保存')

    await openTab(user, '声音与情绪')
    expect(screen.getByRole('combobox', { name: '声音' })).toHaveValue('')
    expect(screen.getByRole('combobox', { name: '声音' })).toBe(voiceInput)
    await act(async () => { await router.navigate('/profiles') })
    expect(await screen.findByText('离开角色编辑？')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '继续编辑', hidden: true }))
    await waitFor(() => expect(screen.queryByText('离开角色编辑？')).not.toBeInTheDocument())
  })

  it('does not restore a language-cleared voice when the save started earlier', async () => {
    const pendingSave = deferred<void>()
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: null, isClone: false },
      { id: 'voice-b', name: 'Sage', languages: '英文', voiceDemo: null, isClone: false },
    ])
    const update = vi.spyOn(profileApi, 'updateProfile').mockReturnValue(pendingSave.promise)
    const { router } = renderEditorRouter('/profiles/profile-a?tab=voice')
    const user = userEvent.setup()
    const voiceInput = await screen.findByRole('combobox', { name: '声音' })

    await user.click(await screen.findByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalledOnce())
    await user.click(screen.getByRole('combobox', { name: '语言' }))
    await user.click((await screen.findAllByText('英文')).at(-1)!)
    await act(async () => {
      pendingSave.resolve()
      await pendingSave.promise
    })
    await screen.findByText('角色设置已保存')

    expect(screen.getByRole('combobox', { name: '声音' })).toHaveValue('')
    expect(screen.getByRole('combobox', { name: '声音' })).toBe(voiceInput)
    await act(async () => { await router.navigate('/profiles') })
    expect(await screen.findByText('离开角色编辑？')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '继续编辑', hidden: true }))
    await waitFor(() => expect(screen.queryByText('离开角色编辑？')).not.toBeInTheDocument())
  })

  it('blocks saving while a restore is pending and unlocks it after completion', async () => {
    const pendingRestore = deferred<void>()
    const restore = vi.spyOn(profileApi, 'restorePrompt').mockReturnValue(pendingRestore.promise)
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '恢复初始提示词' }))
    await user.click(screen.getByRole('button', { name: '确认恢复' }))
    await waitFor(() => expect(restore).toHaveBeenCalled())

    const save = screen.getByRole('button', { name: '保存角色' })
    expect(save).toBeDisabled()
    await user.click(save)
    expect(update).not.toHaveBeenCalled()

    await act(async () => {
      pendingRestore.resolve()
      await pendingRestore.promise
    })
    await waitFor(() => expect(save).toBeEnabled())
    await user.click(save)
    await waitFor(() => expect(update).toHaveBeenCalledOnce())
  })

  it('saves only supported cue names', async () => {
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()
    await screen.findByDisplayValue('小满')
    await user.click(screen.getByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith(
      'profile-a',
      expect.objectContaining({ companionCues: { laugh: true, sigh: false, hesitate: true, breathe: false } }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ))
  })

  it('shows synthesized legacy model and voice selections', async () => {
    vi.spyOn(profileApi, 'getProfile').mockResolvedValue({ ...profile, models: legacyEditableModels })
    renderPage()
    const user = userEvent.setup()

    await openTab(user, 'AI 模型')
    expect(await screen.findByText('陪伴模型')).toBeVisible()
    expect(screen.getAllByText(/自然语音/).length).toBeGreaterThan(0)
    await openTab(user, '声音与情绪')
    expect(await screen.findByRole('button', { name: '试听晴岚' })).toBeVisible()
    expect(modelApi.listModelVoices).toHaveBeenCalledWith('tts-a', undefined, expect.anything())
  })

  it('does not submit untouched synthesized legacy models or voice when renaming', async () => {
    vi.spyOn(profileApi, 'getProfile').mockResolvedValue({ ...profile, models: legacyEditableModels })
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    const name = await screen.findByLabelText('角色名称')
    await user.clear(name)
    await user.type(name, '新名称')
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const input = update.mock.calls[0][1]
    expect(input.name).toBe('新名称')
    expect(input).not.toHaveProperty('models')
    expect(input).not.toHaveProperty('ttsVoiceId')
  })

  it('preserves other synthesized legacy models when changing one model', async () => {
    vi.spyOn(profileApi, 'getProfile').mockResolvedValue({ ...profile, models: legacyEditableModels })
    vi.spyOn(profileApi, 'listProfileModelOptions').mockResolvedValue([
      { id: 'llm-b', modelType: 'LLM', name: '新对话模型', source: 'global', providerCode: 'openai', enabled: true,
        isDefault: false, vendorName: 'OpenAI', protocol: 'OpenAI', credentialStatus: 'not_required', unavailableReason: null },
      ...legacyEditableModels.filter((item) => item.resourceId !== 'llm-a').map((item) => ({
        id: item.resourceId!, modelType: item.modelType, name: `${item.modelType} 模型`, source: 'global' as const,
        providerCode: 'native', enabled: true, isDefault: false, vendorName: '系统', protocol: '原生',
        credentialStatus: 'not_required' as const, unavailableReason: null,
      })),
    ])
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await openTab(user, 'AI 模型')
    await user.click(await screen.findByRole('combobox', { name: '对话模型 LLM' }))
    await user.click(await screen.findByText('新对话模型 · OpenAI'))
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].models).toEqual([
      { modelType: 'LLM', source: 'global', resourceId: 'llm-b' },
      ...legacyEditableModels.slice(1),
    ])
  })

  it('does not clear an untouched legacy voice when changing a non-TTS model', async () => {
    vi.spyOn(profileApi, 'getProfile').mockResolvedValue({
      ...profile,
      ttsModelId: null,
      ttsVoiceId: 'voice-old',
      models: legacyEditableModels.map((item) => item.modelType === 'TTS'
        ? { modelType: 'TTS', source: 'default' as const, enabled: true }
        : item),
    })
    vi.spyOn(profileApi, 'listProfileModelOptions').mockResolvedValue([
      { id: 'llm-b', modelType: 'LLM', name: '新对话模型', source: 'global', providerCode: 'openai', enabled: true,
        isDefault: false, vendorName: 'OpenAI', protocol: 'OpenAI', credentialStatus: 'not_required', unavailableReason: null },
    ])
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await openTab(user, 'AI 模型')
    await user.click(await screen.findByRole('combobox', { name: '对话模型 LLM' }))
    await user.click(await screen.findByText('新对话模型 · OpenAI'))
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1]).toHaveProperty('models')
    expect(update.mock.calls[0][1]).not.toHaveProperty('ttsVoiceId')
  })

  it('allows switching to a server-provided voice', async () => {
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: null, isClone: false },
      { id: 'voice-b', name: '晚风', languages: '中文', voiceDemo: null, isClone: false },
    ])
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()
    await openTab(user, '声音与情绪')
    await user.click(await screen.findByRole('combobox', { name: '声音' }))
    await user.click(await screen.findByText('晚风'))
    await user.click(screen.getByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('profile-a', expect.objectContaining({ ttsVoiceId: 'voice-b' }), expect.anything()))
  })

  it('loads native voices for a changed global TTS and requires a valid selection', async () => {
    vi.spyOn(modelApi, 'listModelVoices')
      .mockResolvedValueOnce([{ id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: null, isClone: false }])
      .mockResolvedValueOnce([{ id: 'voice-b', name: '晚风', languages: '中文', voiceDemo: null, isClone: false }])
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await openTab(user, 'AI 模型')
    await user.click(await screen.findByRole('combobox', { name: '语音合成 TTS' }))
    expect(screen.queryByText('我的语音模型 · 自定义')).not.toBeInTheDocument()
    await user.click(await screen.findByText('备用语音 · 微软'))
    await openTab(user, '声音与情绪')
    expect(screen.getByRole('combobox', { name: '声音' })).toHaveValue('')
    await user.click(screen.getByRole('button', { name: '保存角色' }))
    expect(await screen.findByText('请选择当前 TTS 模型的声音')).toBeVisible()
    expect(update).not.toHaveBeenCalled()

    await user.click(screen.getByRole('combobox', { name: '声音' }))
    await user.click(await screen.findByText('晚风'))
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalledWith('profile-a', expect.objectContaining({
      ttsVoiceId: 'voice-b',
      models: expect.arrayContaining([
        expect.objectContaining({ modelType: 'TTS', source: 'global', resourceId: 'tts-b' }),
      ]),
    }), expect.anything()))
    expect(modelApi.listModelVoices).toHaveBeenLastCalledWith('tts-b', undefined, expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })

  it('clears an incompatible voice, stops preview, and loads voices as soon as TTS changes', async () => {
    const nextVoices = deferred<modelApi.ModelVoice[]>()
    vi.spyOn(modelApi, 'listModelVoices')
      .mockResolvedValueOnce([{ id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: 'https://example.com/qinglan.mp3', isClone: false }])
      .mockReturnValueOnce(nextVoices.promise)
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderEditorRouter('/profiles/profile-a?tab=voice')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '试听晴岚' }))
    await user.click(screen.getByRole('tab', { name: 'AI 模型' }))
    await user.click(await screen.findByRole('combobox', { name: '语音合成 TTS' }))
    await user.click(await screen.findByText('备用语音 · 微软'))

    expect(audio.pause).toHaveBeenCalledOnce()
    expect(modelApi.listModelVoices).toHaveBeenLastCalledWith('tts-b', undefined, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    await user.click(screen.getByRole('tab', { name: '声音与情绪' }))
    expect(screen.getByRole('combobox', { name: '声音' })).toHaveValue('')
    await user.click(screen.getByRole('button', { name: '保存角色' }))
    expect(await screen.findByText('声音列表加载中，请稍后保存')).toBeVisible()
    expect(update).not.toHaveBeenCalled()

    await act(async () => {
      nextVoices.resolve([{ id: 'voice-b', name: '晚风', languages: '中文', voiceDemo: null, isClone: false }])
      await nextVoices.promise
    })
    await user.click(screen.getByRole('button', { name: '保存角色' }))
    expect(await screen.findByText('请选择当前 TTS 模型的声音')).toBeVisible()
  })

  it('preserves untouched private migration bindings when saving ordinary fields', async () => {
    vi.spyOn(profileApi, 'getProfile').mockResolvedValue({
      ...profile,
      ttsModelId: 'private-old',
      ttsVoiceId: 'voice-old',
      models: [{ modelType: 'TTS', source: 'private', resourceId: 'private-old', name: '旧个人语音',
        enabled: false, unavailableReason: '旧个人模型已停用，请重新选择' }],
    })
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await openTab(user, 'AI 模型')
    expect(await screen.findByText('旧个人模型已停用，请重新选择')).toBeVisible()
    expect(modelApi.listModelVoices).not.toHaveBeenCalled()
    await openTab(user, '声音与情绪')
    expect(screen.getByRole('combobox', { name: '声音' })).toHaveValue('')
    await openTab(user, '角色设定')
    const name = screen.getByLabelText('角色名称')
    await user.clear(name)
    await user.type(name, '新名称')
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const input = update.mock.calls[0][1]
    expect(input.name).toBe('新名称')
    expect(input).not.toHaveProperty('models')
    expect(input).not.toHaveProperty('ttsVoiceId')
  })

  it('updates a global TTS voice while preserving an untouched private LLM binding', async () => {
    vi.spyOn(profileApi, 'getProfile').mockResolvedValue({
      ...profile,
      models: [
        { modelType: 'LLM', source: 'private', resourceId: 'private-llm', name: '旧个人对话', enabled: false,
          unavailableReason: '旧个人模型已停用，请重新选择' },
        { modelType: 'TTS', source: 'global', resourceId: 'tts-a' },
      ],
    })
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: null, isClone: false },
      { id: 'voice-b', name: '晚风', languages: '中文', voiceDemo: null, isClone: false },
    ])
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '声音与情绪')
    await user.click(await screen.findByRole('combobox', { name: '声音' }))
    await user.click(await screen.findByText('晚风'))
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const input = update.mock.calls[0][1]
    expect(input).toHaveProperty('ttsVoiceId', 'voice-b')
    expect(input).not.toHaveProperty('models')
  })

  it('blocks partial private migration and saves only after every private binding is replaced', async () => {
    vi.spyOn(profileApi, 'getProfile').mockResolvedValue({
      ...profile,
      ttsModelId: 'private-tts',
      ttsVoiceId: 'voice-old',
      models: [
        { modelType: 'LLM', source: 'private', resourceId: 'private-llm', name: '旧个人对话', enabled: false,
          unavailableReason: '旧个人模型已停用，请重新选择' },
        { modelType: 'TTS', source: 'private', resourceId: 'private-tts', name: '旧个人语音', enabled: false,
          unavailableReason: '旧个人模型已停用，请重新选择' },
      ],
    })
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()
    await screen.findByDisplayValue('小满')

    await openTab(user, 'AI 模型')
    await user.click(screen.getByRole('combobox', { name: '对话模型 LLM' }))
    await user.click(screen.getAllByText('跟随系统默认').at(-1)!)
    await user.click(screen.getByRole('button', { name: '保存角色' }))
    expect(await screen.findByText('请先替换所有已停用的个人模型')).toBeVisible()
    expect(update).not.toHaveBeenCalled()

    await user.click(screen.getByRole('combobox', { name: '语音合成 TTS' }))
    await user.click(screen.getAllByText('跟随系统默认').at(-1)!)
    await openTab(user, '声音与情绪')
    await user.click(screen.getByRole('combobox', { name: '声音' }))
    await user.click(await screen.findByText('晴岚'))
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalledWith('profile-a', expect.objectContaining({
      ttsVoiceId: 'voice-a',
      models: expect.not.arrayContaining([expect.objectContaining({ source: 'private' })]),
    }), expect.anything()))
  })

  it('loads the current default TTS voices and submits the selected voice', async () => {
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()
    await openTab(user, '声音与情绪')
    await screen.findByRole('button', { name: '试听晴岚' })
    vi.mocked(modelApi.listModelVoices).mockClear()

    await openTab(user, 'AI 模型')
    await user.click(screen.getByRole('combobox', { name: '语音合成 TTS' }))
    await user.click(screen.getAllByText('跟随系统默认').at(-1)!)
    await openTab(user, '声音与情绪')
    expect(screen.getByRole('combobox', { name: '声音' })).toHaveValue('')
    await waitFor(() => expect(modelApi.listModelVoices).toHaveBeenCalledWith('tts-a', undefined, expect.anything()))
    await user.click(screen.getByRole('combobox', { name: '声音' }))
    await user.click(await screen.findByText('晴岚'))
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalledWith('profile-a', expect.objectContaining({
      ttsVoiceId: 'voice-a',
      models: expect.arrayContaining([expect.objectContaining({ modelType: 'TTS', source: 'default' })]),
    }), expect.anything()))
  })

  it('does not choose a default TTS voice source when default metadata is ambiguous', async () => {
    vi.spyOn(profileApi, 'listProfileModelOptions').mockResolvedValue([
      { id: 'tts-default-a', modelType: 'TTS', name: '默认语音 A', source: 'global', providerCode: 'edge',
        enabled: true, isDefault: true, vendorName: '微软', protocol: 'Edge TTS', credentialStatus: 'not_required',
        unavailableReason: null },
      { id: 'tts-default-b', modelType: 'TTS', name: '默认语音 B', source: 'global', providerCode: 'edge',
        enabled: true, isDefault: true, vendorName: '微软', protocol: 'Edge TTS', credentialStatus: 'not_required',
        unavailableReason: null },
    ])
    renderPage()
    const user = userEvent.setup()
    await screen.findByDisplayValue('小满')
    vi.mocked(modelApi.listModelVoices).mockClear()

    await openTab(user, 'AI 模型')
    await user.click(screen.getByRole('combobox', { name: '语音合成 TTS' }))
    await user.click(screen.getAllByText('跟随系统默认').at(-1)!)

    expect(modelApi.listModelVoices).not.toHaveBeenCalled()
  })

  it('allows an empty voice when the selected TTS exposes no voices', async () => {
    vi.spyOn(modelApi, 'listModelVoices')
      .mockResolvedValueOnce([{ id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: null, isClone: false }])
      .mockResolvedValueOnce([])
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await openTab(user, 'AI 模型')
    await user.click(await screen.findByRole('combobox', { name: '语音合成 TTS' }))
    await user.click(await screen.findByText('备用语音 · 微软'))
    await waitFor(() => expect(modelApi.listModelVoices).toHaveBeenLastCalledWith('tts-b', undefined, expect.anything()))
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalledWith('profile-a', expect.objectContaining({ ttsVoiceId: '' }), expect.anything()))
  })

  it('does not save while the changed TTS voice list is still loading', async () => {
    const pending = deferred<modelApi.ModelVoice[]>()
    vi.spyOn(modelApi, 'listModelVoices')
      .mockResolvedValueOnce([{ id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: null, isClone: false }])
      .mockReturnValueOnce(pending.promise)
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await openTab(user, 'AI 模型')
    await user.click(await screen.findByRole('combobox', { name: '语音合成 TTS' }))
    await user.click(await screen.findByText('备用语音 · 微软'))
    await openTab(user, '声音与情绪')
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    expect(await screen.findByText('声音列表加载中，请稍后保存')).toBeVisible()
    expect(update).not.toHaveBeenCalled()
    pending.resolve([])
    await pending.promise
  })

  it('allows ordinary saves when the untouched voice list fails to load', async () => {
    vi.spyOn(modelApi, 'listModelVoices').mockRejectedValue(new Error('声音服务不可用'))
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '声音与情绪')
    expect(await screen.findByText('声音服务不可用')).toBeVisible()
    await openTab(user, '角色设定')
    const name = screen.getByLabelText('角色名称')
    await user.clear(name)
    await user.type(name, '新名称')
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].name).toBe('新名称')
    expect(update.mock.calls[0][1]).not.toHaveProperty('models')
    expect(update.mock.calls[0][1]).not.toHaveProperty('ttsVoiceId')
  })

  it('derives language filters from native voice metadata', async () => {
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '晴岚', languages: '中文、英文', voiceDemo: null, isClone: false },
      { id: 'voice-b', name: 'Sage', languages: '英文', voiceDemo: null, isClone: false },
    ])
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '声音与情绪')
    await user.click(await screen.findByRole('combobox', { name: '语言' }))
    expect((await screen.findAllByText('中文')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('英文').length).toBeGreaterThan(0)
    await user.click(screen.getAllByText('中文').at(-1)!)
    await user.click(screen.getByRole('combobox', { name: '声音' }))

    expect(await screen.findByRole('option', { name: '晴岚' })).toBeInTheDocument()
    expect(screen.queryByText('Sage')).not.toBeInTheDocument()
  })

  it('marks a language-cleared voice dirty, stops preview, and saves the empty voice', async () => {
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: 'https://example.com/qinglan.mp3', isClone: false },
      { id: 'voice-b', name: 'Sage', languages: '英文', voiceDemo: null, isClone: false },
    ])
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    const { router } = renderEditorRouter('/profiles/profile-a?tab=voice')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '试听晴岚' }))
    await user.click(screen.getByRole('combobox', { name: '语言' }))
    await user.click((await screen.findAllByText('英文')).at(-1)!)

    expect(screen.getByRole('combobox', { name: '声音' })).toHaveValue('')
    expect(audio.pause).toHaveBeenCalledOnce()
    await act(async () => { await router.navigate('/profiles') })
    expect(await screen.findByText('离开角色编辑？')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '继续编辑', hidden: true }))
    await waitFor(() => expect(screen.queryByText('离开角色编辑？')).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('profile-a', expect.objectContaining({ ttsVoiceId: '' }), expect.anything()))
  })

  it('preserves a saved voice that is not returned until the user changes it', async () => {
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-b', name: '晚风', languages: '中文', voiceDemo: null, isClone: false },
    ])
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '声音与情绪')
    await waitFor(() => expect(screen.getByRole('combobox', { name: '声音' })).toHaveValue(''))
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1]).not.toHaveProperty('ttsVoiceId')
  })

  it('ignores a late voice response after switching TTS models again', async () => {
    const oldRequest = deferred<modelApi.ModelVoice[]>()
    vi.spyOn(modelApi, 'listModelVoices')
      .mockResolvedValueOnce([{ id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: null, isClone: false }])
      .mockReturnValueOnce(oldRequest.promise)
      .mockResolvedValueOnce([{ id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: null, isClone: false }])
    renderPage()
    const user = userEvent.setup()

    await openTab(user, 'AI 模型')
    await user.click(await screen.findByRole('combobox', { name: '语音合成 TTS' }))
    await user.click(await screen.findByText('备用语音 · 微软'))
    await user.click(screen.getByRole('combobox', { name: '语音合成 TTS' }))
    await user.click(await screen.findByText('自然语音 · 微软'))
    await act(async () => {
      oldRequest.resolve([{ id: 'voice-old', name: '过期声音', languages: '中文', voiceDemo: null, isClone: false }])
      await oldRequest.promise
    })
    await openTab(user, '声音与情绪')
    await user.click(screen.getByRole('combobox', { name: '声音' }))

    expect(screen.queryByText('过期声音')).not.toBeInTheDocument()
    expect(await screen.findByRole('option', { name: '晴岚' })).toBeInTheDocument()
  })

  it('previews only voices with demos and cleans up the shared audio element', async () => {
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: 'https://example.com/qinglan.mp3', isClone: false },
      { id: 'voice-b', name: '晚风', languages: '中文', voiceDemo: null, isClone: false },
    ])
    const user = userEvent.setup()
    const view = renderPage()

    expect(Audio).not.toHaveBeenCalled()
    await openTab(user, '声音与情绪')
    expect(await screen.findByRole('button', { name: '试听晴岚' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '试听晚风' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '试听晴岚' }))
    expect(Audio).toHaveBeenCalledTimes(1)
    expect(audio.src).toBe('https://example.com/qinglan.mp3')
    expect(audio.play).toHaveBeenCalledOnce()

    view.unmount()
    expect(audio.pause).toHaveBeenCalledOnce()
    expect(audio.currentTime).toBe(0)
    expect(audio.hasAttribute('src')).toBe(false)
    expect(audio.onerror).toBeNull()
    expect(audio.removeAttribute).toHaveBeenCalledWith('src')
    expect(audio.load).toHaveBeenCalledOnce()
  })

  it('accepts same-origin relative preview URLs', async () => {
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: '/media/qinglan.mp3', isClone: false },
    ])
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '声音与情绪')
    await user.click(await screen.findByRole('button', { name: '试听晴岚' }))

    expect(audio.src).toBe(new URL('/media/qinglan.mp3', window.location.origin).href)
    expect(audio.play).toHaveBeenCalledOnce()
  })

  it('rejects javascript and data preview URLs without creating audio', async () => {
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '脚本声音', languages: '中文', voiceDemo: 'javascript:alert(1)', isClone: false },
      { id: 'voice-b', name: '内联声音', languages: '中文', voiceDemo: 'data:audio/mp3;base64,AA==', isClone: false },
    ])
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '声音与情绪')
    await user.click(await screen.findByRole('button', { name: '试听脚本声音' }))
    await user.click(screen.getByRole('combobox', { name: '声音' }))
    await user.click(await screen.findByText('内联声音'))
    await user.click(screen.getByRole('button', { name: '试听内联声音' }))

    expect(Audio).not.toHaveBeenCalled()
    expect(await screen.findByText('试听失败')).toBeVisible()
  })

  it('ignores a saved old media error handler after a newer preview starts', async () => {
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: 'https://example.com/qinglan.mp3', isClone: false },
      { id: 'voice-b', name: '晚风', languages: '中文', voiceDemo: 'https://example.com/wanfeng.mp3', isClone: false },
    ])
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '声音与情绪')
    await user.click(await screen.findByRole('button', { name: '试听晴岚' }))
    const oldHandler = audio.onerror
    await user.click(screen.getByRole('combobox', { name: '声音' }))
    await user.click(await screen.findByText('晚风'))
    await user.click(screen.getByRole('button', { name: '试听晚风' }))
    const pauseCount = audio.pause.mock.calls.length
    oldHandler?.()

    expect(audio.pause).toHaveBeenCalledTimes(pauseCount)
    expect(audio.src).toBe('https://example.com/wanfeng.mp3')
    expect(screen.queryByText('试听失败')).not.toBeInTheDocument()
  })

  it('isolates stale preview failures and rejects unsafe demo URLs', async () => {
    const oldPlay = deferred<void>()
    audio.play.mockReturnValueOnce(oldPlay.promise).mockResolvedValueOnce(undefined)
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: 'https://example.com/qinglan.mp3', isClone: false },
      { id: 'voice-b', name: '晚风', languages: '中文', voiceDemo: 'https://example.com/wanfeng.mp3', isClone: false },
      { id: 'voice-c', name: '危险声音', languages: '中文', voiceDemo: 'file:///tmp/demo.mp3', isClone: false },
    ])
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '声音与情绪')
    await user.click(await screen.findByRole('button', { name: '试听晴岚' }))
    await user.click(screen.getByRole('combobox', { name: '声音' }))
    await user.click(await screen.findByText('晚风'))
    await user.click(screen.getByRole('button', { name: '试听晚风' }))
    expect(audio.pause).toHaveBeenCalled()
    expect(audio.currentTime).toBe(0)
    expect(audio.removeAttribute).toHaveBeenCalledWith('src')
    expect(audio.load).toHaveBeenCalled()
    oldPlay.reject(new Error('old failure'))
    await oldPlay.promise.catch(() => undefined)
    expect(screen.queryByText('试听失败')).not.toBeInTheDocument()

    audio.onerror?.()
    expect(await screen.findByText('试听失败')).toBeVisible()
    expect(audio.pause).toHaveBeenCalled()
    expect(audio.currentTime).toBe(0)
    expect(audio.hasAttribute('src')).toBe(false)
    expect(audio.onerror).toBeNull()
    expect(audio.removeAttribute).toHaveBeenCalledWith('src')
    expect(audio.load).toHaveBeenCalled()

    await user.click(screen.getByRole('combobox', { name: '声音' }))
    await user.click(await screen.findByText('危险声音'))
    await user.click(screen.getByRole('button', { name: '试听危险声音' }))
    expect(await screen.findByText('试听失败')).toBeVisible()
    expect(Audio).toHaveBeenCalledTimes(1)
    expect(audio.hasAttribute('src')).toBe(false)
  })

  it('reports an active preview play rejection', async () => {
    audio.play.mockRejectedValueOnce(new Error('blocked'))
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-a', name: '晴岚', languages: '中文', voiceDemo: 'https://example.com/qinglan.mp3', isClone: false },
    ])
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '声音与情绪')
    await user.click(await screen.findByRole('button', { name: '试听晴岚' }))

    expect(await screen.findByText('试听失败')).toBeVisible()
    expect(audio.pause).toHaveBeenCalled()
    expect(audio.currentTime).toBe(0)
    expect(audio.hasAttribute('src')).toBe(false)
    expect(audio.onerror).toBeNull()
    expect(audio.removeAttribute).toHaveBeenCalledWith('src')
    expect(audio.load).toHaveBeenCalled()
  })

  it('shows an unavailable bound model with its credential guidance', async () => {
    vi.spyOn(profileApi, 'getProfile').mockResolvedValue({
      ...profile,
      models: [{ modelType: 'LLM', source: 'global', resourceId: 'llm-missing', overrides: {} }],
    })
    vi.spyOn(profileApi, 'listProfileModelOptions').mockResolvedValue([
      { id: 'llm-missing', modelType: 'LLM', name: 'DeepSeek', source: 'global', providerCode: 'openai', enabled: false,
        isDefault: false, vendorName: 'DeepSeek', protocol: 'OpenAI 兼容', credentialStatus: 'missing', unavailableReason: '请先在模型管理中配置凭据' },
    ])

    renderPage()
    const user = userEvent.setup()

    await openTab(user, 'AI 模型')
    expect(await screen.findByText('请先在模型管理中配置凭据')).toBeVisible()
    expect(screen.getByText('请联系管理员处理模型配置')).toBeVisible()
    expect(screen.queryByRole('link', { name: '前往模型管理' })).not.toBeInTheDocument()
  })

  it('blocks saving another model change while a missing-credential binding remains', async () => {
    vi.spyOn(profileApi, 'getProfile').mockResolvedValue({
      ...profile,
      models: [
        { modelType: 'LLM', source: 'global', resourceId: 'llm-missing', name: 'DeepSeek', enabled: true,
          unavailableReason: '请先配置凭据' },
        { modelType: 'TTS', source: 'global', resourceId: 'tts-a' },
      ],
    })
    vi.spyOn(profileApi, 'listProfileModelOptions').mockResolvedValue([
      { id: 'llm-missing', modelType: 'LLM', name: 'DeepSeek', source: 'global', providerCode: 'openai', enabled: true,
        isDefault: false, vendorName: 'DeepSeek', protocol: 'OpenAI 兼容', credentialStatus: 'missing', unavailableReason: '请先配置凭据' },
      { id: 'asr-b', modelType: 'ASR', name: '新识别模型', source: 'global', providerCode: 'funasr', enabled: true,
        isDefault: false, vendorName: '阿里云', protocol: 'DashScope', credentialStatus: 'configured', unavailableReason: null },
      { id: 'tts-a', modelType: 'TTS', name: '自然语音', source: 'global', providerCode: 'edge', enabled: true,
        isDefault: true, vendorName: '微软', protocol: 'Edge TTS', credentialStatus: 'not_required', unavailableReason: null },
    ])
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await openTab(user, 'AI 模型')
    await user.click(await screen.findByRole('combobox', { name: '语音识别 ASR' }))
    await user.click(await screen.findByText('新识别模型 · 阿里云'))
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    expect(await screen.findByText('DeepSeek 未配置凭据，无法保存')).toBeVisible()
    expect(update).not.toHaveBeenCalled()
  })

  it('loads more snapshot pages until total is reached', async () => {
    const firstPage = Array.from({ length: 10 }, (_, index) => ({
      id: `snapshot-${11 - index}`, versionNo: 11 - index, source: 'companion-update', createdAt: '2026-07-29T10:00:00Z',
    }))
    vi.spyOn(profileApi, 'listProfileVersions')
      .mockResolvedValueOnce({ total: 11, list: firstPage })
      .mockResolvedValueOnce({ total: 11, list: [{ id: 'snapshot-1', versionNo: 1, source: 'initial', createdAt: '2026-07-20T10:00:00Z' }] })
    renderPage()
    const user = userEvent.setup()
    await openTab(user, '版本记录')
    await user.click(await screen.findByRole('button', { name: '加载更多版本' }))
    expect(await screen.findByText('版本 1 · 初始版本')).toBeVisible()
    expect(profileApi.listProfileVersions).toHaveBeenLastCalledWith('profile-a', 2, 10, 11, expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })

  it('restores a confirmed version into the shared draft and waits for final save', async () => {
    vi.spyOn(profileApi, 'getProfileVersion').mockResolvedValue({
      id: 'snapshot-2', versionNo: 2, source: 'companion-update', createdAt: '2026-07-29T10:00:00Z',
      snapshot: {
        name: '旧版小满', relationMode: 'lover', userAddress: '队长', personality: '活泼', systemPrompt: '旧版提示词',
        companionCues: { laugh: false, sigh: true, hesitate: false, breathe: true },
        screenExpressionEnabled: false, cameraPreferenceEnabled: true, ttsVoiceId: 'voice-old',
        modelResourceIds: { LLM: 'llm-old', ASR: null, TTS: 'tts-a', VAD: null, VLLM: null, Memory: null },
      },
    })
    vi.spyOn(profileApi, 'listProfileModelOptions').mockResolvedValue([
      { id: 'llm-old', modelType: 'LLM', name: '旧版对话', source: 'global', providerCode: 'openai', enabled: true,
        isDefault: false, vendorName: 'OpenAI', protocol: 'OpenAI 兼容', credentialStatus: 'configured', unavailableReason: null },
      { id: 'tts-a', modelType: 'TTS', name: '自然语音', source: 'global', providerCode: 'edge', enabled: true,
        isDefault: true, vendorName: '微软', protocol: 'Edge TTS', credentialStatus: 'not_required', unavailableReason: null },
    ])
    vi.spyOn(modelApi, 'listModelVoices').mockResolvedValue([
      { id: 'voice-old', name: '旧版声音', languages: '中文', voiceDemo: null, isClone: false },
    ])
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    expect(await screen.findByLabelText('角色名称')).toHaveValue('小满')
    await openTab(user, '版本记录')
    await user.click(await screen.findByRole('button', { name: '恢复版本 2' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('恢复后会替换当前未保存草稿，服务端配置要到保存角色后才会更新。')).toBeInTheDocument()
    expect(profileApi.getProfileVersion).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: '确认恢复' }))

    await openTab(user, '角色设定')
    expect(await screen.findByDisplayValue('旧版小满')).toBeVisible()
    expect(screen.getByDisplayValue('旧版提示词')).toBeVisible()
    expect(update).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('profile-a', expect.objectContaining({
      name: '旧版小满', relationMode: 'lover', userAddress: '队长', personality: '活泼', systemPrompt: '旧版提示词',
      ttsVoiceId: 'voice-old', screenExpressionEnabled: false, cameraPreferenceEnabled: true,
      models: expect.arrayContaining([
        expect.objectContaining({ modelType: 'LLM', source: 'global', resourceId: 'llm-old' }),
        expect.objectContaining({ modelType: 'ASR', source: 'default' }),
        expect.objectContaining({ modelType: 'TTS', source: 'default' }),
      ]),
    }), expect.objectContaining({ signal: expect.any(AbortSignal) })))
  })

  it('does not submit a missing-credential model restored from a version', async () => {
    vi.spyOn(profileApi, 'getProfileVersion').mockResolvedValue({
      id: 'snapshot-2', versionNo: 2, source: 'companion-update', createdAt: '2026-07-29T10:00:00Z',
      snapshot: {
        name: '旧版小满', relationMode: 'friend', userAddress: '小夏', personality: '温柔', systemPrompt: '旧版提示词',
        companionCues: { laugh: true, sigh: false, hesitate: false, breathe: false },
        screenExpressionEnabled: true, cameraPreferenceEnabled: false, ttsVoiceId: 'voice-a',
        modelResourceIds: { LLM: 'llm-missing', ASR: null, TTS: 'tts-a', VAD: null, VLLM: null, Memory: null },
      },
    })
    vi.spyOn(profileApi, 'listProfileModelOptions').mockResolvedValue([
      { id: 'llm-missing', modelType: 'LLM', name: 'DeepSeek', source: 'global', providerCode: 'openai', enabled: true,
        isDefault: false, vendorName: 'DeepSeek', protocol: 'OpenAI 兼容', credentialStatus: 'missing', unavailableReason: '请先配置凭据' },
      { id: 'tts-a', modelType: 'TTS', name: '自然语音', source: 'global', providerCode: 'edge', enabled: true,
        isDefault: true, vendorName: '微软', protocol: 'Edge TTS', credentialStatus: 'not_required', unavailableReason: null },
    ])
    const update = vi.spyOn(profileApi, 'updateProfile').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '版本记录')
    await user.click(await screen.findByRole('button', { name: '恢复版本 2' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '确认恢复' }))
    expect(await screen.findByText('DeepSeek 未配置凭据，已改为跟随系统默认')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '保存角色' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const input = update.mock.calls[0][1]
    expect(input.models).toContainEqual({ modelType: 'LLM', source: 'default' })
    expect(input.models).not.toContainEqual(expect.objectContaining({ resourceId: 'llm-missing' }))
  })

  it('locks cancellation while a confirmed version detail is loading', async () => {
    const pendingVersion = deferred<profileApi.ProfileVersionDetail>()
    vi.spyOn(profileApi, 'getProfileVersion').mockReturnValue(pendingVersion.promise)
    renderPage()
    const user = userEvent.setup()

    expect(await screen.findByDisplayValue('小满')).toBeVisible()
    await openTab(user, '版本记录')
    await user.click(await screen.findByRole('button', { name: '恢复版本 2' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '确认恢复' }))
    expect(within(dialog).getByRole('button', { name: /取\s*消/ })).toBeDisabled()
    expect(within(dialog).queryByRole('button', { name: 'Close' })).not.toBeInTheDocument()
    await act(async () => {
      pendingVersion.resolve({
        id: 'snapshot-2', versionNo: 2, source: 'companion-update', createdAt: '2026-07-29T10:00:00Z',
        snapshot: {
          name: '不应恢复', relationMode: 'lover', userAddress: '队长', personality: '活泼', systemPrompt: '不应恢复',
          companionCues: { laugh: false, sigh: false, hesitate: false, breathe: false },
          screenExpressionEnabled: false, cameraPreferenceEnabled: true, ttsVoiceId: null,
          modelResourceIds: { LLM: null, ASR: null, TTS: null, VAD: null, VLLM: null, Memory: null },
        },
      })
      await pendingVersion.promise
    })

    await openTab(user, '角色设定')
    expect(screen.getByLabelText('角色名称')).toHaveValue('不应恢复')
  })

  it('keeps the first-page version anchor when newer snapshots are inserted', async () => {
    const firstPage = Array.from({ length: 10 }, (_, index) => ({
      id: `snapshot-${20 - index}`, versionNo: 20 - index, source: 'companion-update', createdAt: '2026-07-29T10:00:00Z',
    }))
    const secondPage = Array.from({ length: 10 }, (_, index) => ({
      id: `snapshot-${10 - index}`, versionNo: 10 - index, source: index === 9 ? 'initial' : 'companion-update', createdAt: '2026-07-20T10:00:00Z',
    }))
    vi.spyOn(profileApi, 'listProfileVersions')
      .mockResolvedValueOnce({ total: 20, list: firstPage })
      .mockResolvedValueOnce({ total: 20, list: secondPage })
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '版本记录')
    await user.click(await screen.findByRole('button', { name: '加载更多版本' }))

    expect(await screen.findByText('版本 1 · 初始版本')).toBeVisible()
    expect(profileApi.listProfileVersions).toHaveBeenNthCalledWith(2, 'profile-a', 2, 10, 20, expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })

  it('orders and deduplicates anchored snapshot pages', async () => {
    const firstPage = [
      { id: 'snapshot-9', versionNo: 9, source: 'companion-update', createdAt: '2026-07-29T10:00:00Z' },
      { id: 'snapshot-10', versionNo: 10, source: 'companion-update', createdAt: '2026-07-29T10:00:00Z' },
      ...Array.from({ length: 8 }, (_, index) => ({
        id: `snapshot-${8 - index}`, versionNo: 8 - index, source: 'companion-update', createdAt: '2026-07-29T10:00:00Z',
      })),
    ]
    vi.spyOn(profileApi, 'listProfileVersions')
      .mockResolvedValueOnce({ total: 11, list: firstPage })
      .mockResolvedValueOnce({ total: 11, list: [
        { id: 'snapshot-2', versionNo: 2, source: 'companion-update', createdAt: '2026-07-20T10:00:00Z' },
        { id: 'snapshot-1', versionNo: 1, source: 'initial', createdAt: '2026-07-20T10:00:00Z' },
      ] })
    renderPage()
    const user = userEvent.setup()

    await openTab(user, '版本记录')
    await user.click(await screen.findByRole('button', { name: '加载更多版本' }))

    const labels = screen.getAllByText(/^版本 \d+/).map((item) => item.textContent)
    expect(labels[0]).toContain('版本 10')
    expect(labels.at(-1)).toContain('版本 1')
    expect(screen.getAllByText('版本 2 · 角色设置更新')).toHaveLength(1)
  })

  it('ignores an old load-more response after restore refreshes history', async () => {
    const latePage = deferred<{ total: number; list: profileApi.ProfileVersion[] }>()
    const initialPage = Array.from({ length: 10 }, (_, index) => ({
      id: `snapshot-${11 - index}`, versionNo: 11 - index, source: 'companion-update', createdAt: '2026-07-29T10:00:00Z',
    }))
    vi.spyOn(profileApi, 'listProfileVersions')
      .mockResolvedValueOnce({ total: 11, list: initialPage })
      .mockReturnValueOnce(latePage.promise)
      .mockResolvedValueOnce({ total: 1, list: [
        { id: 'snapshot-12', versionNo: 12, source: 'companion-restore-prompt', createdAt: '2026-07-29T11:00:00Z' },
      ] })
    vi.spyOn(profileApi, 'restorePrompt').mockResolvedValue(undefined)
    vi.spyOn(profileApi, 'getProfile')
      .mockResolvedValueOnce(profile)
      .mockResolvedValueOnce({ ...profile, systemPrompt: '初始提示词' })
    renderPage()
    const user = userEvent.setup()
    await openTab(user, '版本记录')
    await user.click(await screen.findByRole('button', { name: '加载更多版本' }))
    await openTab(user, '角色设定')
    await user.click(screen.getByRole('button', { name: '恢复初始提示词' }))
    await user.click(screen.getByRole('button', { name: '确认恢复' }))
    await openTab(user, '版本记录')
    expect(await screen.findByText('版本 12 · 恢复初始提示词')).toBeVisible()

    await act(async () => {
      latePage.resolve({ total: 11, list: [
        { id: 'snapshot-1', versionNo: 1, source: 'initial', createdAt: '2026-07-20T10:00:00Z' },
      ] })
      await latePage.promise
    })

    await waitFor(() => expect(screen.queryByText('版本 1 · 初始版本')).not.toBeInTheDocument())
  })

  it('uses the decoded route id', async () => {
    renderPage('profile /?#%')
    await screen.findByDisplayValue('小满')
    expect(profileApi.getProfile).toHaveBeenCalledWith('profile /?#%', expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })

  it('clears the old editor immediately when route loading switches and then fails', async () => {
    const nextProfile = deferred<profileApi.CompanionProfile>()
    vi.spyOn(profileApi, 'getProfile')
      .mockResolvedValueOnce(profile)
      .mockReturnValueOnce(nextProfile.promise)
    renderPageWithRouteControls()
    const user = userEvent.setup()
    await openTab(user, '声音与情绪')
    await user.click(await screen.findByRole('button', { name: '试听晴岚' }))

    await user.click(screen.getByRole('button', { name: '切换角色' }))

    expect(screen.queryByDisplayValue('小满')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存角色' })).not.toBeInTheDocument()
    expect(audio.hasAttribute('src')).toBe(false)
    await act(async () => {
      nextProfile.reject(new Error('新角色加载失败'))
      await nextProfile.promise.catch(() => undefined)
    })
    expect(await screen.findByText('新角色加载失败')).toBeVisible()
    expect(screen.queryByDisplayValue('小满')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存角色' })).not.toBeInTheDocument()
  })

  it('resets a pending save when switching profiles and ignores its late success', async () => {
    const pendingSave = deferred<void>()
    const nextProfile = { ...profile, id: 'profile-b', name: '小雨' }
    vi.spyOn(profileApi, 'getProfile').mockResolvedValueOnce(profile).mockResolvedValueOnce(nextProfile)
    const update = vi.spyOn(profileApi, 'updateProfile').mockReturnValue(pendingSave.promise)
    renderPageWithRouteControls()
    const user = userEvent.setup()
    await screen.findByDisplayValue('小满')

    await user.click(screen.getByRole('button', { name: '保存角色' }))
    await waitFor(() => expect(update).toHaveBeenCalled())
    const signal = update.mock.calls[0][2]?.signal
    expect(screen.getByRole('button', { name: '保存角色' })).toHaveClass('ant-btn-loading')
    await user.click(screen.getByRole('button', { name: '切换角色' }))

    expect(await screen.findByDisplayValue('小雨')).toBeVisible()
    expect(signal?.aborted).toBe(true)
    expect(screen.getByRole('button', { name: '保存角色' })).not.toHaveClass('ant-btn-loading')
    await act(async () => {
      pendingSave.resolve()
      await pendingSave.promise
    })
    expect(screen.getByDisplayValue('小雨')).toBeVisible()
    expect(screen.queryByText('角色设置已保存')).not.toBeInTheDocument()
  })

  it('closes an open restore dialog when switching profiles', async () => {
    const nextProfile = { ...profile, id: 'profile-b', name: '小雨' }
    vi.spyOn(profileApi, 'getProfile').mockResolvedValueOnce(profile).mockResolvedValueOnce(nextProfile)
    renderPageWithRouteControls()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '恢复初始提示词' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '切换角色' }))

    expect(await screen.findByDisplayValue('小雨')).toBeVisible()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('does not let a late restore failure from the old profile affect a new restore dialog', async () => {
    const pendingRestore = deferred<void>()
    const nextProfile = { ...profile, id: 'profile-b', name: '小雨' }
    vi.spyOn(profileApi, 'getProfile').mockResolvedValueOnce(profile).mockResolvedValueOnce(nextProfile)
    const restore = vi.spyOn(profileApi, 'restorePrompt').mockReturnValue(pendingRestore.promise)
    renderPageWithRouteControls()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '恢复初始提示词' }))
    await user.click(screen.getByRole('button', { name: '确认恢复' }))
    await waitFor(() => expect(restore).toHaveBeenCalled())
    const signal = restore.mock.calls[0][1]?.signal
    await user.click(screen.getByRole('button', { name: '切换角色' }))
    await screen.findByDisplayValue('小雨')
    await user.click(screen.getByRole('button', { name: '恢复初始提示词' }))

    expect(signal?.aborted).toBe(true)
    await act(async () => {
      pendingRestore.reject(new Error('旧角色恢复失败'))
      await pendingRestore.promise.catch(() => undefined)
    })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.queryByText('完整提示词已恢复')).not.toBeInTheDocument()
    expect(screen.queryByText('旧角色恢复失败')).not.toBeInTheDocument()
  })
})
