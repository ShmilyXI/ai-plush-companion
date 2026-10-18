import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getSystemSettings, listTimbres, saveSystemSettings, type SystemSettings } from '../../api/admin'
import { SystemSettingsPage } from './SystemSettingsPage'

vi.mock('../../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/admin')>()
  return { ...actual, getSystemSettings: vi.fn(), saveSystemSettings: vi.fn(), listTimbres: vi.fn() }
})

const settings: SystemSettings = {
  publicWebsocketUrl: 'wss://pet.example/ws',
  publicOtaUrl: 'https://pet.example/ota/',
  zixuanListenHost: '0.0.0.0',
  zixuanListenPort: 8000,
  otaListenHost: '0.0.0.0',
  otaListenPort: 8002,
  defaultLlmModelId: 'llm-1',
  defaultVllmModelId: 'vllm-1',
  defaultTtsModelId: 'tts-1',
  defaultAsrModelId: 'asr-1',
  defaultVadModelId: 'vad-1',
  defaultMemoryModelId: 'memory-1',
  defaultTtsVoiceId: 'voice-1',
  modelOptions: {
    LLM: [{ id: 'llm-1', name: '对话模型', type: 'LLM' }],
    VLLM: [{ id: 'vllm-1', name: '视觉模型', type: 'VLLM' }],
    TTS: [{ id: 'tts-1', name: '语音模型', type: 'TTS' }, { id: 'tts-2', name: '备用语音', type: 'TTS' }],
    ASR: [{ id: 'asr-1', name: '识别模型', type: 'ASR' }],
    VAD: [{ id: 'vad-1', name: '检测模型', type: 'VAD' }],
    Memory: [{ id: 'memory-1', name: '记忆模型', type: 'Memory' }],
  },
  voices: [{ id: 'voice-1', name: '温柔女声', type: 'TTS_VOICE' }],
  health: {
    zixuan: { status: 'unknown', address: '0.0.0.0:8000', checkedAt: '2026-08-04T00:00:00Z' },
    ota: { status: 'available', address: '127.0.0.1:8002', checkedAt: '2026-08-04T00:00:00Z' },
  },
  restartRequired: false,
  restartServices: [],
}

describe('SystemSettingsPage', () => {
  beforeEach(() => {
    vi.mocked(getSystemSettings).mockResolvedValue(settings)
    vi.mocked(saveSystemSettings).mockResolvedValue(settings)
    vi.mocked(listTimbres).mockResolvedValue({ list: [], total: 0 })
  })

  it('loads the three safe settings groups and service health', async () => {
    render(<SystemSettingsPage />)

    expect(await screen.findByText('设备连接')).toBeInTheDocument()
    expect(screen.getByText('本地服务')).toBeInTheDocument()
    expect(screen.getByText('默认 AI 资源')).toBeInTheDocument()
    expect(screen.getByText('启用表示资源可被选择，不代表设备正在使用。')).toBeInTheDocument()
    expect(screen.getByText('0.0.0.0:8000')).toBeInTheDocument()
    expect(screen.getByText('127.0.0.1:8002')).toBeInTheDocument()
    expect(screen.queryByLabelText(/密码|密钥|Token/i)).not.toBeInTheDocument()
  })

  it('preserves edited values when saving fails', async () => {
    const user = userEvent.setup()
    vi.mocked(saveSystemSettings).mockRejectedValue(new Error('保存失败'))
    render(<SystemSettingsPage />)

    const websocket = await screen.findByLabelText('公开 WebSocket 地址')
    await user.clear(websocket)
    await user.type(websocket, 'wss://new.example/ws')
    await user.click(screen.getByRole('button', { name: '保存设置' }))

    expect(await screen.findByDisplayValue('wss://new.example/ws')).toBeInTheDocument()
    expect(screen.getByText('保存失败')).toBeInTheDocument()
  })

  it('uses the refreshed result and reports required restart after saving', async () => {
    const user = userEvent.setup()
    vi.mocked(saveSystemSettings).mockResolvedValue({ ...settings, restartRequired: true, restartServices: ['zixuan'] })
    render(<SystemSettingsPage />)

    await user.click(await screen.findByRole('button', { name: '保存设置' }))

    await waitFor(() => expect(saveSystemSettings).toHaveBeenCalled())
    expect(await screen.findByText('设置已保存，zixuan 服务需重启后生效')).toBeInTheDocument()
  })

  it('validates endpoint schemes before saving', async () => {
    const user = userEvent.setup()
    render(<SystemSettingsPage />)

    const websocket = await screen.findByLabelText('公开 WebSocket 地址')
    await user.clear(websocket)
    await user.type(websocket, 'https://wrong.example/ws')
    await user.click(screen.getByRole('button', { name: '保存设置' }))

    expect(await screen.findByText('地址格式不正确')).toBeInTheDocument()
    expect(saveSystemSettings).not.toHaveBeenCalled()
  }, 10_000)

  it('loads voices for a newly selected TTS model', async () => {
    const user = userEvent.setup()
    vi.mocked(listTimbres).mockResolvedValue({
      list: [{ id: 'voice-2', name: '备用音色', languages: 'zh-CN', ttsModelId: 'tts-2', ttsVoice: 'voice-2', sort: 0 }],
      total: 1,
    })
    render(<SystemSettingsPage />)

    await user.click(await screen.findByLabelText('默认 TTS'))
    await user.click(await screen.findByText('备用语音'))

    await waitFor(() => expect(listTimbres).toHaveBeenCalledWith('tts-2', '', 1, 100, expect.anything()))
  })

  it('offers a retry after the initial load fails', async () => {
    const user = userEvent.setup()
    vi.mocked(getSystemSettings).mockRejectedValueOnce(new Error('加载失败')).mockResolvedValueOnce(settings)
    render(<SystemSettingsPage />)

    const error = await screen.findByText('加载失败')
    const alert = error.closest('.ant-alert')
    expect(alert).not.toBeNull()
    await user.click(within(alert as HTMLElement).getByRole('button', { name: /重\s*试/ }))

    expect(await screen.findByText('设备连接')).toBeInTheDocument()
    expect(getSystemSettings).toHaveBeenCalledTimes(2)
  })
})
