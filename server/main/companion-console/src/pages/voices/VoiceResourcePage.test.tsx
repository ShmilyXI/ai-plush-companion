import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as voiceApi from '../../api/voiceResources'
import { useAuthStore } from '../../auth/authStore'
import { VoiceResourcePage } from './VoiceResourcePage'

vi.mock('../../api/voiceResources', async () => {
  const actual = await vi.importActual<typeof import('../../api/voiceResources')>('../../api/voiceResources')
  return {
    ...actual,
    listVoiceResources: vi.fn(),
    createVoiceResources: vi.fn(),
    deleteVoiceResources: vi.fn(),
    listTtsPlatforms: vi.fn(),
    listAssignableUsers: vi.fn(),
  }
})

const resource: voiceApi.VoiceResource = {
  id: 'clone-1', name: '温柔声线', modelId: 'tts-1', modelName: '火山双流', voiceId: 'S_voice_1',
  languages: 'zh-CN', userId: '42', userName: 'tester', trainStatus: 0, trainError: null,
  createDate: '2026-08-08T10:00:00.000+08:00', hasVoice: false,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

function session(superAdmin: 0 | 1) {
  useAuthStore.getState().setSessionForTest({ token: 'token', user: { id: '1', username: 'admin', superAdmin, status: 1 } })
}

describe('VoiceResourcePage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    session(1)
    vi.mocked(voiceApi.listVoiceResources).mockResolvedValue({ total: 1, list: [resource] })
    vi.mocked(voiceApi.listTtsPlatforms).mockResolvedValue([
      { id: 'tts-1', modelName: '火山双流' }, { id: 'tts-2', modelName: '另一平台' },
    ])
    vi.mocked(voiceApi.listAssignableUsers).mockResolvedValue({ total: 1, list: [{ id: '42', mobile: '13800138000' }] })
    vi.mocked(voiceApi.createVoiceResources).mockResolvedValue(undefined)
    vi.mocked(voiceApi.deleteVoiceResources).mockResolvedValue(undefined)
  })

  it('loads the super-admin resource page and exposes allocation operations', async () => {
    render(<VoiceResourcePage />)
    expect(await screen.findByRole('heading', { name: '音色资源' })).toBeInTheDocument()
    expect(voiceApi.listVoiceResources).toHaveBeenCalledWith({
      page: 1, limit: 20, name: '', orderField: 'create_date', order: 'desc',
    }, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(await screen.findByText('S_voice_1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '分配音色资源' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '删除温柔声线' })).toBeEnabled()
  })

  it('does not call super-admin endpoints or display mutations to a normal user', () => {
    session(0)
    render(<VoiceResourcePage />)
    expect(screen.getByRole('alert')).toHaveTextContent('仅超级管理员可管理音色资源')
    expect(screen.queryByRole('button', { name: '分配音色资源' })).not.toBeInTheDocument()
    expect(voiceApi.listVoiceResources).not.toHaveBeenCalled()
    expect(voiceApi.listTtsPlatforms).not.toHaveBeenCalled()
  })

  it('allocates multiple voice ids to a selected platform and string user id', async () => {
    const user = userEvent.setup()
    render(<VoiceResourcePage />)
    await user.click(await screen.findByRole('button', { name: '分配音色资源' }))
    const dialog = await screen.findByRole('dialog', { name: '分配音色资源' })

    fireEvent.mouseDown(within(dialog).getByLabelText('TTS 平台'))
    await user.click((await screen.findAllByText('火山双流')).at(-1)!)
    const ids = within(dialog).getByLabelText('音色 ID')
    await user.type(ids, 'S_one,S_two,')
    const account = within(dialog).getByLabelText('归属账号')
    await user.type(account, '138')
    await waitFor(() => expect(voiceApi.listAssignableUsers).toHaveBeenCalledWith('138', 1, 20, expect.objectContaining({ signal: expect.any(AbortSignal) })))
    await user.click(await screen.findByText('13800138000'))
    await user.type(within(dialog).getByLabelText('语言'), 'zh-CN')
    await user.click(within(dialog).getByRole('button', { name: /确.*定/ }))

    await waitFor(() => expect(voiceApi.createVoiceResources).toHaveBeenCalledWith({
      modelId: 'tts-1', voiceIds: ['S_one', 'S_two'], userId: '42', languages: 'zh-CN',
    }))
    await waitFor(() => expect(voiceApi.listVoiceResources).toHaveBeenCalledTimes(2))
  })

  it('requires confirmation before deleting and refreshes the native page', async () => {
    const user = userEvent.setup()
    render(<VoiceResourcePage />)
    await user.click(await screen.findByRole('button', { name: '删除温柔声线' }))
    expect(voiceApi.deleteVoiceResources).not.toHaveBeenCalled()
    const confirm = await waitFor(() => document.querySelector<HTMLButtonElement>('.ant-popconfirm-buttons .ant-btn-primary'))
    await user.click(confirm!)
    await waitFor(() => expect(voiceApi.deleteVoiceResources).toHaveBeenCalledWith(['clone-1']))
    await waitFor(() => expect(voiceApi.listVoiceResources).toHaveBeenCalledTimes(2))
  })

  it('clears platform-specific voice ids when the TTS platform changes', async () => {
    const user = userEvent.setup()
    render(<VoiceResourcePage />)
    await user.click(await screen.findByRole('button', { name: '分配音色资源' }))
    const dialog = await screen.findByRole('dialog', { name: '分配音色资源' })
    const platform = within(dialog).getByLabelText('TTS 平台')
    fireEvent.mouseDown(platform)
    await user.click((await screen.findAllByText('火山双流')).at(-1)!)
    const ids = within(dialog).getByLabelText('音色 ID')
    await user.type(ids, 'S_old,')
    expect(dialog.querySelector('.ant-select-selection-item[title="S_old"]')).toBeInTheDocument()

    fireEvent.mouseDown(platform)
    await user.click(await screen.findByText('另一平台'))

    expect(dialog.querySelector('.ant-select-selection-item[title="S_old"]')).not.toBeInTheDocument()
  })

  it('selects all rows and deletes the selected resource ids together', async () => {
    vi.mocked(voiceApi.listVoiceResources).mockResolvedValue({ total: 2, list: [
      resource, { ...resource, id: 'clone-2', name: '沉稳声线', voiceId: 'S_voice_2' },
    ] })
    const user = userEvent.setup()
    render(<VoiceResourcePage />)
    const checkboxes = await screen.findAllByRole('checkbox')
    await user.click(checkboxes[0])
    await user.click(screen.getByRole('button', { name: '删除所选音色资源' }))
    const confirm = await waitFor(() => document.querySelector<HTMLButtonElement>('.ant-popconfirm-buttons .ant-btn-primary'))
    await user.click(confirm!)

    await waitFor(() => expect(voiceApi.deleteVoiceResources).toHaveBeenCalledWith(['clone-1', 'clone-2']))
  })

  it('ignores an old allocation completion after the editor closes and reopens', async () => {
    const pending = deferred<void>()
    vi.mocked(voiceApi.createVoiceResources).mockReturnValueOnce(pending.promise)
    const user = userEvent.setup()
    render(<VoiceResourcePage />)
    await user.click(await screen.findByRole('button', { name: '分配音色资源' }))
    let dialog = await screen.findByRole('dialog', { name: '分配音色资源' })
    fireEvent.mouseDown(within(dialog).getByLabelText('TTS 平台'))
    await user.click((await screen.findAllByText('火山双流')).at(-1)!)
    await user.type(within(dialog).getByLabelText('音色 ID'), 'S_one,')
    const account = within(dialog).getByLabelText('归属账号')
    await user.type(account, '138')
    await user.click(await screen.findByText('13800138000'))
    await user.type(within(dialog).getByLabelText('语言'), 'zh-CN')
    await user.click(within(dialog).getByRole('button', { name: /确.*定/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Close' }))
    await user.click(screen.getByRole('button', { name: '分配音色资源' }))
    dialog = await screen.findByRole('dialog', { name: '分配音色资源' })
    pending.resolve()
    await pending.promise
    expect(dialog).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: /确.*定/ })).toBeEnabled()
  })
})
