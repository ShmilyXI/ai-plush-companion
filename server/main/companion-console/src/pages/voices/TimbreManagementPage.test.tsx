import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as voiceApi from '../../api/volcengineVoices'
import { TimbreManagementPage } from './TimbreManagementPage'

vi.mock('../../api/volcengineVoices', async () => {
  const actual = await vi.importActual<typeof import('../../api/volcengineVoices')>('../../api/volcengineVoices')
  return { ...actual, listVolcengineVoices: vi.fn() }
})

const voices: voiceApi.VolcengineVoice[] = [{
  id: 'voice-a', name: '温柔女声', voiceType: 'voice-a', gender: '女', age: '青年',
  languages: '中文、英文', tags: ['热门', '温柔'], description: '温柔自然',
  trialUrl: 'https://example.com/a.mp3',
}, {
  id: 'voice-b', name: '沉稳男声', voiceType: 'voice-b', gender: '男', age: null,
  languages: '中文', tags: [], description: null, trialUrl: null,
}]

const audio = {
  pause: vi.fn(), play: vi.fn<() => Promise<void>>(), currentTime: 0, src: '',
  onerror: null as null | (() => void), onended: null as null | (() => void),
}

function renderPage() {
  return render(<MemoryRouter><TimbreManagementPage /></MemoryRouter>)
}

describe('TimbreManagementPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(voiceApi.listVolcengineVoices).mockResolvedValue({ total: voices.length, list: voices })
    audio.pause.mockReset()
    audio.play.mockReset().mockResolvedValue(undefined)
    audio.currentTime = 0
    audio.src = ''
    audio.onerror = null
    audio.onended = null
    vi.stubGlobal('Audio', vi.fn(() => audio))
  })

  it('loads Volcengine 1.0 by default and renders a read-only catalog', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: '音色库' })).toBeInTheDocument()
    await waitFor(() => expect(voiceApi.listVolcengineVoices).toHaveBeenCalledWith(
      { resourceId: 'seed-tts-1.0', page: 1, limit: 20, name: '' },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ))
    expect(await screen.findByText('中文、英文')).toBeInTheDocument()
    expect(screen.getByText('热门')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '新增音色' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /编辑/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /删除/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '试听温柔女声' })).toBeEnabled()
    expect(screen.getByText('暂无试听')).toBeInTheDocument()
  })

  it('switches to Volcengine 2.0 and resets the page', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('温柔女声')
    await user.click(screen.getByRole('combobox', { name: '语音合成模型' }))
    await user.click(await screen.findByText('语音合成 2.0'))

    await waitFor(() => expect(voiceApi.listVolcengineVoices).toHaveBeenLastCalledWith(
      { resourceId: 'seed-tts-2.0', page: 1, limit: 20, name: '' },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ))
  })

  it('searches names, previews trial URLs, and reports failures', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(await screen.findByRole('searchbox', { name: '音色名称' }), ' 温柔 ')
    await user.click(screen.getByRole('button', { name: /搜\s*索/ }))
    await waitFor(() => expect(voiceApi.listVolcengineVoices).toHaveBeenLastCalledWith(
      { resourceId: 'seed-tts-1.0', page: 1, limit: 20, name: '温柔' }, expect.anything(),
    ))

    await user.click(screen.getByRole('button', { name: '试听温柔女声' }))
    expect(audio.src).toBe('https://example.com/a.mp3')
    expect(screen.getByRole('button', { name: '停止温柔女声' })).toBeEnabled()

    vi.mocked(voiceApi.listVolcengineVoices).mockRejectedValueOnce(new Error('火山接口失败'))
    await user.click(screen.getByRole('button', { name: /重\s*置/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('火山接口失败')
  })
})
