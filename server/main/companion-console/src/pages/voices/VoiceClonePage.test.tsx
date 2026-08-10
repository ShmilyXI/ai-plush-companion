import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, useLocation } from 'react-router-dom'

import * as cloneApi from '../../api/voiceClones'
import type { VoiceResource } from '../../api/voiceResources'
import { useAuthStore } from '../../auth/authStore'
import { VoiceClonePage } from './VoiceClonePage'
import * as sampleEditor from './voiceSampleEditor'

vi.mock('../../api/voiceClones', async () => {
  const actual = await vi.importActual<typeof import('../../api/voiceClones')>('../../api/voiceClones')
  return {
    ...actual,
    listVoiceClones: vi.fn(), uploadVoiceSample: vi.fn(), updateVoiceCloneName: vi.fn(),
    getVoiceAudioUuid: vi.fn(), getVoiceClonePlayUrl: vi.fn(), cloneVoiceAudio: vi.fn(),
  }
})

vi.mock('./voiceSampleEditor', async () => {
  const actual = await vi.importActual<typeof import('./voiceSampleEditor')>('./voiceSampleEditor')
  return { ...actual, readSafeVoiceMetadata: vi.fn() }
})

const waiting: VoiceResource = {
  id: 'clone-1', name: '温柔声线', modelId: 'tts-1', modelName: '火山双流', voiceId: 'S_voice_1',
  languages: 'zh-CN', userId: '42', userName: 'tester', trainStatus: 0, trainError: null,
  createDate: '2026-08-08T10:00:00.000+08:00', hasVoice: false,
}
const failed: VoiceResource = {
  ...waiting, id: 'clone-2', name: '训练失败声线', voiceId: 'S_voice_2', hasVoice: true,
  trainStatus: 3, trainError: '供应商训练失败',
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

const audio = {
  pause: vi.fn(), play: vi.fn<() => Promise<void>>(), currentTime: 0, src: '',
  onerror: null as null | (() => void), onended: null as null | (() => void),
}

function audioBuffer(duration: number, length = Math.round(duration * 10), numberOfChannels = 1, sampleRate = 10) {
  const channels = Array.from({ length: numberOfChannels }, () => new Float32Array(length).fill(0.25))
  return {
    duration,
    length,
    sampleRate,
    numberOfChannels,
    getChannelData: vi.fn((channel: number) => channels[channel]),
  }
}

let decodedBuffer = audioBuffer(12)
const audioContext = {
  decodeAudioData: vi.fn(async () => decodedBuffer),
  createBuffer: vi.fn((channels: number, length: number, sampleRate: number) => audioBuffer(length / sampleRate, length, channels, sampleRate)),
  close: vi.fn(),
}

function CurrentLocation() {
  const location = useLocation()
  return <output aria-label="当前地址">{location.pathname}{location.search}</output>
}

describe('VoiceClonePage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    useAuthStore.getState().setSessionForTest({ token: 'token', user: { id: '42', username: 'tester', superAdmin: 0, status: 1 } })
    vi.mocked(cloneApi.listVoiceClones).mockResolvedValue({ total: 2, list: [waiting, failed] })
    vi.mocked(cloneApi.uploadVoiceSample).mockResolvedValue(undefined)
    vi.mocked(cloneApi.updateVoiceCloneName).mockResolvedValue(undefined)
    vi.mocked(cloneApi.cloneVoiceAudio).mockResolvedValue(undefined)
    vi.mocked(cloneApi.getVoiceAudioUuid).mockResolvedValue('123e4567-e89b-42d3-a456-426614174000')
    vi.mocked(cloneApi.getVoiceClonePlayUrl).mockReturnValue('https://manager.example/xiaozhi/voiceClone/play/123e4567-e89b-42d3-a456-426614174000')
    audio.pause.mockReset()
    audio.play.mockReset().mockResolvedValue(undefined)
    audio.currentTime = 0
    audio.src = ''
    audio.onerror = null
    audio.onended = null
    vi.stubGlobal('Audio', vi.fn(() => audio))
    decodedBuffer = audioBuffer(12)
    audioContext.decodeAudioData.mockClear()
    audioContext.createBuffer.mockClear()
    audioContext.close.mockClear()
    vi.stubGlobal('AudioContext', vi.fn(() => audioContext))
    vi.mocked(sampleEditor.readSafeVoiceMetadata).mockResolvedValue(12)
    vi.stubGlobal('URL', class extends globalThis.URL {
      static createObjectURL = vi.fn(() => 'blob:https://console.example/sample')
      static revokeObjectURL = vi.fn()
    })
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined)
    vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => undefined)
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
  })

  it('keeps failed records visible with their returned status and error', async () => {
    render(<VoiceClonePage />)
    expect(await screen.findByRole('heading', { name: '音色克隆' })).toBeInTheDocument()
    expect(await screen.findByText('训练失败声线')).toBeInTheDocument()
    expect(screen.getByText('训练失败')).toBeInTheDocument()
    expect(screen.getByText('供应商训练失败')).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: '训练失败声线' })).not.toBeInTheDocument()
  })

  it('guides an administrator to allocate a resource when the clone list is empty', async () => {
    useAuthStore.getState().setSessionForTest({ token: 'token', user: { id: '1', username: 'admin', superAdmin: 1, status: 1 } })
    vi.mocked(cloneApi.listVoiceClones).mockResolvedValue({ total: 0, list: [] })
    const user = userEvent.setup()

    render(<MemoryRouter initialEntries={['/voices?tab=clone']}><VoiceClonePage /><CurrentLocation /></MemoryRouter>)

    expect(await screen.findByText('尚未分配音色资源')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('table').closest('.ant-spin-container')).not.toHaveClass('ant-spin-blur'))
    await user.click(screen.getByRole('button', { name: '前往音色资源' }))
    expect(screen.getByLabelText('当前地址')).toHaveTextContent('/voices?tab=resources')
  })

  it('asks an ordinary user to contact an administrator when the clone list is empty', async () => {
    vi.mocked(cloneApi.listVoiceClones).mockResolvedValue({ total: 0, list: [] })

    render(<VoiceClonePage />)

    expect(await screen.findByText('请联系管理员分配音色资源')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '前往音色资源' })).not.toBeInTheDocument()
  })

  it('shows a neutral empty search result without allocation guidance', async () => {
    vi.mocked(cloneApi.listVoiceClones).mockResolvedValue({ total: 0, list: [] })
    const user = userEvent.setup()
    render(<VoiceClonePage />)

    await screen.findByText('请联系管理员分配音色资源')
    await user.type(screen.getByLabelText('音色名称或 ID'), 'missing')
    await user.keyboard('{Enter}')

    expect(await screen.findByText('未找到匹配的音色资源')).toBeInTheDocument()
    expect(screen.queryByText('尚未分配音色资源')).not.toBeInTheDocument()
    expect(screen.queryByText('请联系管理员分配音色资源')).not.toBeInTheDocument()
  })

  it('moves from file selection to an editable preview and can return to reselect', async () => {
    const user = userEvent.setup()
    const view = render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '上传温柔声线样本' }))
    const dialog = await screen.findByRole('dialog', { name: '上传音频样本' })
    const file = new File(['sample'], 'voice.mp3', { type: 'audio/mpeg' })
    Object.defineProperty(file, 'arrayBuffer', { value: vi.fn().mockResolvedValue(new ArrayBuffer(8)) })
    fireEvent.change(within(dialog).getByLabelText('音频文件'), { target: { files: [file] } })
    expect(await within(dialog).findByText('步骤 2 音频试听与编辑')).toBeInTheDocument()
    expect(within(dialog).getByText('当前时长 12.0 秒')).toBeInTheDocument()
    expect(cloneApi.uploadVoiceSample).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: '试听样本' }))
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(1)
    await user.click(within(dialog).getByRole('button', { name: '返回重选' }))
    expect(await within(dialog).findByText('步骤 1 选择音频')).toBeInTheDocument()
    expect(URL.revokeObjectURL).toHaveBeenCalled()
    view.unmount()
    expect(audioContext.close).toHaveBeenCalledTimes(1)
  })

  it('trims the selected waveform, validates 8–60 seconds, then uploads the edited WAV', async () => {
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '上传温柔声线样本' }))
    const dialog = await screen.findByRole('dialog', { name: '上传音频样本' })
    const file = new File(['sample'], 'voice.wav', { type: 'audio/wav' })
    Object.defineProperty(file, 'arrayBuffer', { value: vi.fn().mockResolvedValue(new ArrayBuffer(8)) })
    fireEvent.change(within(dialog).getByLabelText('音频文件'), { target: { files: [file] } })
    const canvas = await within(dialog).findByLabelText('音频波形')
    vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue({
      width: 100, height: 100, left: 0, right: 100, top: 0, bottom: 100, x: 0, y: 0, toJSON: () => ({}),
    })
    fireEvent.mouseDown(canvas, { clientX: 10 })
    fireEvent.mouseMove(canvas, { clientX: 90 })
    fireEvent.mouseUp(canvas)
    expect(within(dialog).getByText('已选 9.6 秒')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '裁剪所选' }))
    await user.click(within(dialog).getByRole('button', { name: '上传样本' }))

    await waitFor(() => expect(cloneApi.uploadVoiceSample).toHaveBeenCalledWith('clone-1', expect.objectContaining({
      name: 'voice-sample.wav', type: 'audio/wav',
    })))
    await waitFor(() => expect(cloneApi.listVoiceClones).toHaveBeenCalledTimes(2))
  })

  it('shows a short-trim error without discarding the editor state or selection', async () => {
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '上传温柔声线样本' }))
    const dialog = await screen.findByRole('dialog', { name: '上传音频样本' })
    const file = new File(['sample'], 'voice.wav', { type: 'audio/wav' })
    Object.defineProperty(file, 'arrayBuffer', { value: vi.fn().mockResolvedValue(new ArrayBuffer(8)) })
    fireEvent.change(within(dialog).getByLabelText('音频文件'), { target: { files: [file] } })
    const canvas = await within(dialog).findByLabelText('音频波形')
    vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue({
      width: 100, height: 100, left: 0, right: 100, top: 0, bottom: 100, x: 0, y: 0, toJSON: () => ({}),
    })
    fireEvent.mouseDown(canvas, { clientX: 10 })
    fireEvent.mouseMove(canvas, { clientX: 50 })
    fireEvent.mouseUp(canvas)
    await user.click(within(dialog).getByRole('button', { name: '裁剪所选' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('音频时长必须在 8 到 60 秒之间')
    expect(within(dialog).getByText('已选 4.8 秒')).toBeInTheDocument()
    expect(within(dialog).getByText('步骤 2 音频试听与编辑')).toBeInTheDocument()
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
  })

  it('shows a trim encoding-limit error without discarding the waveform or selection', async () => {
    decodedBuffer = audioBuffer(60, 60 * 44_100, 2, 44_100)
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '上传温柔声线样本' }))
    const dialog = await screen.findByRole('dialog', { name: '上传音频样本' })
    const file = new File(['sample'], 'voice.wav', { type: 'audio/wav' })
    Object.defineProperty(file, 'arrayBuffer', { value: vi.fn().mockResolvedValue(new ArrayBuffer(8)) })
    fireEvent.change(within(dialog).getByLabelText('音频文件'), { target: { files: [file] } })
    const canvas = await within(dialog).findByLabelText('音频波形')
    vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue({
      width: 100, height: 100, left: 0, right: 100, top: 0, bottom: 100, x: 0, y: 0, toJSON: () => ({}),
    })
    fireEvent.mouseDown(canvas, { clientX: 0 })
    fireEvent.mouseMove(canvas, { clientX: 100 })
    fireEvent.mouseUp(canvas)
    await user.click(within(dialog).getByRole('button', { name: '裁剪所选' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('WAV 音频不能超过 10MB')
    expect(within(dialog).getByText('已选 60.0 秒')).toBeInTheDocument()
    expect(within(dialog).getByText('步骤 2 音频试听与编辑')).toBeInTheDocument()
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
  })

  it('uploads an untrimmed MP3 as a decoded WAV file', async () => {
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '上传温柔声线样本' }))
    const dialog = await screen.findByRole('dialog', { name: '上传音频样本' })
    const file = new File(['sample'], 'voice.mp3', { type: 'audio/mpeg' })
    Object.defineProperty(file, 'arrayBuffer', { value: vi.fn().mockResolvedValue(new ArrayBuffer(8)) })
    fireEvent.change(within(dialog).getByLabelText('音频文件'), { target: { files: [file] } })
    await within(dialog).findByText('当前时长 12.0 秒')
    await user.click(within(dialog).getByRole('button', { name: '上传样本' }))
    await waitFor(() => expect(cloneApi.uploadVoiceSample).toHaveBeenCalledWith('clone-1', expect.objectContaining({
      name: 'voice-sample.wav', type: 'audio/wav',
    })))
  })

  it('keeps a 60-second stereo sample editable when final WAV encoding exceeds 10MB', async () => {
    decodedBuffer = audioBuffer(60, 60 * 44_100, 2, 44_100)
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '上传温柔声线样本' }))
    const dialog = await screen.findByRole('dialog', { name: '上传音频样本' })
    const file = new File(['sample'], 'voice.wav', { type: 'audio/wav' })
    Object.defineProperty(file, 'arrayBuffer', { value: vi.fn().mockResolvedValue(new ArrayBuffer(8)) })
    fireEvent.change(within(dialog).getByLabelText('音频文件'), { target: { files: [file] } })
    await within(dialog).findByText('当前时长 60.0 秒')
    await user.click(within(dialog).getByRole('button', { name: '上传样本' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('WAV 音频不能超过 10MB')
    expect(within(dialog).getByText('当前时长 60.0 秒')).toBeInTheDocument()
    expect(within(dialog).getByText('步骤 2 音频试听与编辑')).toBeInTheDocument()
    expect(cloneApi.uploadVoiceSample).not.toHaveBeenCalled()
  })

  it('cleans the audio context when decoded PCM exceeds the local limit', async () => {
    decodedBuffer = audioBuffer(12)
    Object.defineProperty(decodedBuffer, 'length', { value: sampleEditor.MAX_DECODED_PCM_BYTES / 4 + 1 })
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '上传温柔声线样本' }))
    const file = new File(['sample'], 'voice.mp3', { type: 'audio/mpeg' })
    Object.defineProperty(file, 'arrayBuffer', { value: vi.fn().mockResolvedValue(new ArrayBuffer(8)) })
    fireEvent.change(screen.getByLabelText('音频文件'), { target: { files: [file] } })
    expect(await screen.findByRole('alert')).toHaveTextContent('解码后的音频过大')
    expect(audioContext.close).toHaveBeenCalledTimes(1)
  })

  it('ignores an old upload failure after closing and reopening the upload editor', async () => {
    const pending = deferred<void>()
    vi.mocked(cloneApi.uploadVoiceSample).mockReturnValueOnce(pending.promise)
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '上传温柔声线样本' }))
    let dialog = await screen.findByRole('dialog', { name: '上传音频样本' })
    const file = new File(['sample'], 'voice.mp3', { type: 'audio/mpeg' })
    Object.defineProperty(file, 'arrayBuffer', { value: vi.fn().mockResolvedValue(new ArrayBuffer(8)) })
    fireEvent.change(within(dialog).getByLabelText('音频文件'), { target: { files: [file] } })
    await within(dialog).findByText('当前时长 12.0 秒')
    await user.click(within(dialog).getByRole('button', { name: '上传样本' }))
    await user.click(within(dialog).getByRole('button', { name: 'Close' }))
    await user.click(screen.getByRole('button', { name: '上传温柔声线样本' }))
    dialog = await screen.findByRole('dialog', { name: '上传音频样本' })
    pending.reject(new Error('old upload failed'))
    await pending.promise.catch(() => undefined)
    expect(dialog).toBeInTheDocument()
    expect(screen.queryByText('old upload failed')).not.toBeInTheDocument()
  })

  it.each([7.9, 60.1])('does not upload a sample with duration %s seconds', async (duration) => {
    decodedBuffer = audioBuffer(duration)
    vi.mocked(sampleEditor.readSafeVoiceMetadata).mockRejectedValueOnce(new RangeError('音频时长必须在 8 到 60 秒之间'))
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '上传温柔声线样本' }))
    const dialog = await screen.findByRole('dialog', { name: '上传音频样本' })
    const file = new File(['sample'], 'voice.mp3', { type: 'audio/mpeg' })
    Object.defineProperty(file, 'arrayBuffer', { value: vi.fn().mockResolvedValue(new ArrayBuffer(8)) })
    fireEvent.change(within(dialog).getByLabelText('音频文件'), { target: { files: [file] } })
    expect(await screen.findByRole('alert')).toHaveTextContent('音频时长必须在 8 到 60 秒之间')
    expect(audioContext.decodeAudioData).not.toHaveBeenCalled()
    expect(cloneApi.uploadVoiceSample).not.toHaveBeenCalled()
  })

  it('edits the clone name through updateName and refreshes', async () => {
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '编辑温柔声线名称' }))
    const dialog = await screen.findByRole('dialog', { name: '编辑音色名称' })
    const input = within(dialog).getByLabelText('音色名称')
    await user.clear(input)
    await user.type(input, '新名字')
    await user.click(within(dialog).getByRole('button', { name: /保.*存/ }))
    await waitFor(() => expect(cloneApi.updateVoiceCloneName).toHaveBeenCalledWith('clone-1', '新名字'))
    await waitFor(() => expect(cloneApi.listVoiceClones).toHaveBeenCalledTimes(2))
  })

  it('ignores an old rename success after closing and reopening another name editor', async () => {
    const pending = deferred<void>()
    vi.mocked(cloneApi.updateVoiceCloneName).mockReturnValueOnce(pending.promise)
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '编辑温柔声线名称' }))
    let dialog = await screen.findByRole('dialog', { name: '编辑音色名称' })
    await user.click(within(dialog).getByRole('button', { name: /保.*存/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Close' }))
    await user.click(screen.getByRole('button', { name: '编辑训练失败声线名称' }))
    dialog = await screen.findByRole('dialog', { name: '编辑音色名称' })
    pending.resolve()
    await pending.promise
    expect(within(dialog).getByLabelText('音色名称')).toHaveValue('训练失败声线')
    expect(within(dialog).getByRole('button', { name: /保.*存/ })).toBeEnabled()
  })

  it('submits cloning locally and refreshes even when the refreshed row remains failed', async () => {
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '克隆训练失败声线' }))
    await waitFor(() => expect(cloneApi.cloneVoiceAudio).toHaveBeenCalledWith('clone-2'))
    await waitFor(() => expect(cloneApi.listVoiceClones).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('供应商训练失败')).toBeInTheDocument()
  })

  it('creates audio only after the UUID arrives, then cleans it on unmount', async () => {
    const pending = deferred<string>()
    vi.mocked(cloneApi.getVoiceAudioUuid).mockReturnValueOnce(pending.promise)
    const user = userEvent.setup()
    const view = render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '试听训练失败声线' }))
    expect(Audio).not.toHaveBeenCalled()
    pending.resolve('123e4567-e89b-42d3-a456-426614174000')
    await pending.promise
    await waitFor(() => expect(Audio).toHaveBeenCalledTimes(1))
    expect(audio.play).toHaveBeenCalledTimes(1)
    view.unmount()
    expect(audio.pause).toHaveBeenCalledTimes(1)
    expect(audio.src).toBe('')
    expect(audio.onerror).toBeNull()
    expect(audio.onended).toBeNull()
  })

  it('ignores an old UUID after switching previews', async () => {
    const firstUuid = deferred<string>()
    vi.mocked(cloneApi.getVoiceAudioUuid).mockReturnValueOnce(firstUuid.promise).mockResolvedValueOnce('123e4567-e89b-42d3-a456-426614174001')
    vi.mocked(cloneApi.listVoiceClones).mockResolvedValue({ total: 2, list: [
      { ...failed, id: 'clone-2' }, { ...failed, id: 'clone-3', name: '另一个失败声线' },
    ] })
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '试听训练失败声线' }))
    await user.click(await screen.findByRole('button', { name: '试听另一个失败声线' }))
    firstUuid.resolve('123e4567-e89b-42d3-a456-426614174000')
    await firstUuid.promise
    await waitFor(() => expect(cloneApi.getVoiceClonePlayUrl).toHaveBeenCalledTimes(1))
    expect(Audio).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('ignores a late play rejection after a newer preview starts', async () => {
    const firstPlay = deferred<void>()
    audio.play.mockReturnValueOnce(firstPlay.promise).mockResolvedValueOnce(undefined)
    vi.mocked(cloneApi.listVoiceClones).mockResolvedValue({ total: 2, list: [
      { ...failed, id: 'clone-2' }, { ...failed, id: 'clone-3', name: '另一个失败声线' },
    ] })
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '试听训练失败声线' }))
    await waitFor(() => expect(audio.play).toHaveBeenCalledTimes(1))
    await user.click(await screen.findByRole('button', { name: '试听另一个失败声线' }))
    await waitFor(() => expect(audio.play).toHaveBeenCalledTimes(2))
    firstPlay.reject(new Error('old failure'))
    await firstPlay.promise.catch(() => undefined)
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('rejects a non-http play URL without constructing audio', async () => {
    vi.mocked(cloneApi.getVoiceClonePlayUrl).mockReturnValue('file:///tmp/voice.wav')
    const user = userEvent.setup()
    render(<VoiceClonePage />)
    await user.click(await screen.findByRole('button', { name: '试听训练失败声线' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('试听失败')
    expect(Audio).not.toHaveBeenCalled()
  })
})
