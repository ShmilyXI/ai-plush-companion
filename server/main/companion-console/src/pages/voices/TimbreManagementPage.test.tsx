import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as timbreApi from '../../api/timbres'
import * as modelApi from '../../api/xiaozhiModels'
import { TimbreManagementPage } from './TimbreManagementPage'

vi.mock('../../api/timbres', () => ({
  listTimbres: vi.fn(),
  createTimbre: vi.fn(),
  updateTimbre: vi.fn(),
  deleteTimbres: vi.fn(),
}))

vi.mock('../../api/xiaozhiModels', async () => {
  const actual = await vi.importActual<typeof import('../../api/xiaozhiModels')>('../../api/xiaozhiModels')
  return { ...actual, listModelNames: vi.fn() }
})

const voices: timbreApi.Timbre[] = [{
  id: 'voice-1', ttsModelId: 'TTS_EdgeTTS', name: '温柔女声', ttsVoice: 'zh-CN-XiaoxiaoNeural',
  languages: '中文', voiceDemo: 'https://example.com/demo.mp3', remark: '自然', sort: 1,
}, {
  id: 'voice-2', ttsModelId: 'TTS_EdgeTTS', name: '沉稳男声', ttsVoice: 'zh-CN-YunxiNeural',
  languages: '中文、英文', voiceDemo: null, remark: null, sort: null,
}]

const audio = {
  pause: vi.fn(),
  play: vi.fn<() => Promise<void>>(),
  currentTime: 0,
  src: '',
  onerror: null as null | (() => void),
  onended: null as null | (() => void),
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

function QueryControls() {
  const navigate = useNavigate()
  return <button onClick={() => navigate('/admin/voices?ttsModelId=TTS_CosyVoice')}>切换查询模型</button>
}

function renderPage(entry = '/admin/voices?ttsModelId=TTS_EdgeTTS') {
  return render(<MemoryRouter initialEntries={[entry]}><TimbreManagementPage /></MemoryRouter>)
}

function renderPageWithQueryControls() {
  return render(<MemoryRouter initialEntries={['/admin/voices?ttsModelId=TTS_EdgeTTS']}>
    <QueryControls />
    <TimbreManagementPage />
  </MemoryRouter>)
}

describe('TimbreManagementPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(modelApi.listModelNames).mockResolvedValue([
      { id: 'TTS_EdgeTTS', modelName: 'Edge TTS' },
      { id: 'TTS_CosyVoice', modelName: 'CosyVoice' },
    ])
    vi.mocked(timbreApi.listTimbres).mockResolvedValue({ total: voices.length, list: voices })
    vi.mocked(timbreApi.createTimbre).mockResolvedValue(undefined)
    vi.mocked(timbreApi.updateTimbre).mockResolvedValue(undefined)
    vi.mocked(timbreApi.deleteTimbres).mockResolvedValue(undefined)
    audio.pause.mockReset()
    audio.play.mockReset().mockResolvedValue(undefined)
    audio.currentTime = 0
    audio.src = ''
    audio.onerror = null
    audio.onended = null
    vi.stubGlobal('Audio', vi.fn(() => audio))
  })

  it('reads the model query, loads native models, and displays languages', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: 'TTS 音色管理' })).toBeInTheDocument()
    expect(modelApi.listModelNames).toHaveBeenCalledWith('TTS', undefined, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    await waitFor(() => expect(timbreApi.listTimbres).toHaveBeenCalledWith(
      { ttsModelId: 'TTS_EdgeTTS', page: 1, limit: 20, name: '' },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ))
    expect(await screen.findByText('中文、英文')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '试听温柔女声' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '试听沉稳男声' })).not.toBeInTheDocument()
    expect(screen.getByText('暂无试听样本')).toBeInTheDocument()
  })

  it('stops the active preview on a second click and resets after playback ends', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '试听温柔女声' }))
    expect(screen.getByRole('button', { name: '停止温柔女声' })).toBeEnabled()

    audio.onended?.()
    expect(await screen.findByRole('button', { name: '试听温柔女声' })).toBeEnabled()

    await user.click(screen.getByRole('button', { name: '试听温柔女声' }))
    await user.click(screen.getByRole('button', { name: '停止温柔女声' }))
    expect(audio.pause).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '试听温柔女声' })).toBeEnabled()
  })

  it('creates a timbre with the precise native DTO mapping', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '新增音色' }))
    const dialog = await screen.findByRole('dialog', { name: '新增音色' })
    await user.type(within(dialog).getByLabelText('音色名称'), '清亮女声')
    await user.type(within(dialog).getByLabelText('音色编码'), 'zh-CN-XiaoyiNeural')
    const languages = within(dialog).getByLabelText('语言')
    await user.clear(languages)
    await user.type(languages, '中文')
    await user.type(within(dialog).getByLabelText('试听地址'), 'https://example.com/xiaoyi.mp3')
    await user.type(within(dialog).getByLabelText('参考音频'), 'https://example.com/reference.wav')
    await user.type(within(dialog).getByLabelText('参考文本'), '你好')
    await user.type(within(dialog).getByLabelText('备注'), '清亮')
    await user.click(within(dialog).getByRole('button', { name: /保.*存/ }))

    await waitFor(() => expect(timbreApi.createTimbre).toHaveBeenCalledWith({
      ttsModelId: 'TTS_EdgeTTS',
      name: '清亮女声',
      ttsVoice: 'zh-CN-XiaoyiNeural',
      languages: '中文',
      sort: 0,
      voiceDemo: 'https://example.com/xiaoyi.mp3',
      remark: '清亮',
      referenceAudio: 'https://example.com/reference.wav',
      referenceText: '你好',
    }))
  })

  it('reuses one audio element, stops the old preview, and cleans up on unmount', async () => {
    vi.mocked(timbreApi.listTimbres).mockResolvedValue({ total: 2, list: [
      voices[0],
      { ...voices[1], voiceDemo: 'https://example.com/yunxi.mp3' },
    ] })
    const user = userEvent.setup()
    const view = renderPage()

    expect(Audio).not.toHaveBeenCalled()
    expect(audio.play).not.toHaveBeenCalled()
    await user.click(await screen.findByRole('button', { name: '试听温柔女声' }))
    expect(Audio).toHaveBeenCalledTimes(1)
    expect(audio.src).toBe('https://example.com/demo.mp3')
    expect(audio.play).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: '试听沉稳男声' }))
    expect(Audio).toHaveBeenCalledTimes(1)
    expect(audio.pause).toHaveBeenCalledTimes(1)
    expect(audio.currentTime).toBe(0)
    expect(audio.src).toBe('https://example.com/yunxi.mp3')

    view.unmount()
    expect(audio.pause).toHaveBeenCalledTimes(2)
    expect(audio.src).toBe('')
    expect(audio.onerror).toBeNull()
    expect(audio.onended).toBeNull()
  })

  it('shows a preview failure without mutating the timbre', async () => {
    audio.play.mockRejectedValueOnce(new Error('blocked'))
    const user = userEvent.setup()
    renderPage()

    const button = await screen.findByRole('button', { name: '试听温柔女声' })
    await user.click(button)

    expect(await screen.findByRole('alert')).toHaveTextContent('试听失败')
    expect(timbreApi.updateTimbre).not.toHaveBeenCalled()
    expect(timbreApi.createTimbre).not.toHaveBeenCalled()
    expect(timbreApi.deleteTimbres).not.toHaveBeenCalled()
  })

  it('handles asynchronous media errors and detaches the old handler when switching previews', async () => {
    vi.mocked(timbreApi.listTimbres).mockResolvedValue({ total: 2, list: [
      voices[0],
      { ...voices[1], voiceDemo: 'https://example.com/yunxi.mp3' },
    ] })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '试听温柔女声' }))
    const oldHandler = audio.onerror
    expect(oldHandler).toBeTypeOf('function')
    await user.click(screen.getByRole('button', { name: '试听沉稳男声' }))
    expect(audio.onerror).not.toBe(oldHandler)
    audio.onerror?.()

    expect(await screen.findByRole('alert')).toHaveTextContent('试听失败')
  })

  it('ignores a late rejection from an old preview after a newer preview starts', async () => {
    const firstPlay = deferred<void>()
    audio.play.mockReturnValueOnce(firstPlay.promise).mockResolvedValueOnce(undefined)
    vi.mocked(timbreApi.listTimbres).mockResolvedValue({ total: 2, list: [
      voices[0],
      { ...voices[1], voiceDemo: 'https://example.com/yunxi.mp3' },
    ] })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '试听温柔女声' }))
    await user.click(screen.getByRole('button', { name: '试听沉稳男声' }))
    firstPlay.reject(new Error('old failure'))
    await firstPlay.promise.catch(() => undefined)

    await waitFor(() => expect(audio.play).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('rejects non-http preview URLs without constructing an audio element', async () => {
    vi.mocked(timbreApi.listTimbres).mockResolvedValue({ total: 1, list: [{ ...voices[0], voiceDemo: 'file:///tmp/demo.mp3' }] })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '试听温柔女声' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('试听失败')
    expect(Audio).not.toHaveBeenCalled()
    expect(audio.play).not.toHaveBeenCalled()
  })

  it('syncs a changed ttsModelId query and resets the list to its first page', async () => {
    const user = userEvent.setup()
    renderPageWithQueryControls()

    await waitFor(() => expect(timbreApi.listTimbres).toHaveBeenCalledWith(
      expect.objectContaining({ ttsModelId: 'TTS_EdgeTTS', page: 1 }), expect.anything(),
    ))
    await user.click(screen.getByRole('button', { name: '切换查询模型' }))

    await waitFor(() => expect(timbreApi.listTimbres).toHaveBeenLastCalledWith(
      { ttsModelId: 'TTS_CosyVoice', page: 1, limit: 20, name: '' }, expect.anything(),
    ))
  })

  it('does not let an old save close a newer editor or refresh its old model view', async () => {
    const pending = deferred<void>()
    vi.mocked(timbreApi.createTimbre).mockReturnValueOnce(pending.promise)
    const user = userEvent.setup()
    renderPageWithQueryControls()

    await user.click(await screen.findByRole('button', { name: '新增音色' }))
    let dialog = await screen.findByRole('dialog', { name: '新增音色' })
    await user.type(within(dialog).getByLabelText('音色名称'), '等待保存')
    await user.type(within(dialog).getByLabelText('音色编码'), 'pending')
    await user.click(within(dialog).getByRole('button', { name: /保.*存/ }))
    await user.click(within(dialog).getByRole('button', { name: /取.*消/ }))
    await user.click(screen.getByRole('button', { name: '切换查询模型' }))
    await waitFor(() => expect(timbreApi.listTimbres).toHaveBeenLastCalledWith(
      expect.objectContaining({ ttsModelId: 'TTS_CosyVoice' }), expect.anything(),
    ))
    await user.click(screen.getByRole('button', { name: '编辑温柔女声' }))
    dialog = await screen.findByRole('dialog', { name: '编辑音色' })

    pending.resolve()
    await pending.promise

    expect(await screen.findByRole('dialog', { name: '编辑音色' })).toBeInTheDocument()
    expect(within(dialog).getByLabelText('音色名称')).toHaveValue('温柔女声')
    expect(vi.mocked(timbreApi.listTimbres).mock.calls.at(-1)?.[0].ttsModelId).toBe('TTS_CosyVoice')
  })

  it('does not refresh an old filter after delete completes following search and pagination changes', async () => {
    const pending = deferred<void>()
    vi.mocked(timbreApi.deleteTimbres).mockReturnValueOnce(pending.promise)
    vi.mocked(timbreApi.listTimbres).mockResolvedValue({ total: 21, list: voices })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '删除温柔女声' }))
    const confirm = await waitFor(() => document.querySelector<HTMLButtonElement>('.ant-popconfirm-buttons .ant-btn-primary'))
    await user.click(confirm!)
    await user.type(screen.getByRole('searchbox', { name: '音色名称' }), '新筛选{enter}')
    await waitFor(() => expect(timbreApi.listTimbres).toHaveBeenLastCalledWith(
      expect.objectContaining({ name: '新筛选', page: 1 }), expect.anything(),
    ))
    await user.click(screen.getByTitle('2'))
    await waitFor(() => expect(timbreApi.listTimbres).toHaveBeenLastCalledWith(
      expect.objectContaining({ name: '新筛选', page: 2 }), expect.anything(),
    ))

    pending.resolve()
    await pending.promise

    expect(vi.mocked(timbreApi.listTimbres).mock.calls.at(-1)?.[0]).toMatchObject({ name: '新筛选', page: 2 })
  })
})
