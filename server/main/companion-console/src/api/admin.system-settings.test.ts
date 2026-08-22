import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from './http'
import { getSystemSettings, saveSystemSettings, type SystemSettingsInput } from './admin'

const input: SystemSettingsInput = {
  publicWebsocketUrl: 'wss://pet.example/ws',
  publicOtaUrl: 'https://pet.example/ota/',
  xiaozhiListenHost: '0.0.0.0',
  xiaozhiListenPort: 8000,
  otaListenHost: '0.0.0.0',
  otaListenPort: 8002,
  defaultLlmModelId: 'llm-1',
  defaultVllmModelId: 'vllm-1',
  defaultTtsModelId: 'tts-1',
  defaultAsrModelId: 'asr-1',
  defaultVadModelId: 'vad-1',
  defaultMemoryModelId: 'memory-1',
  defaultTtsVoiceId: 'voice-1',
}

const payload = {
  ...input,
  modelOptions: { LLM: [{ id: 'llm-1', name: '对话模型', type: 'LLM' }] },
  voices: [{ id: 'voice-1', name: '温柔女声', type: 'TTS_VOICE' }],
  health: {
    xiaozhi: { status: 'available', address: '127.0.0.1:8000', checkedAt: '2026-08-04T00:00:00Z' },
    ota: { status: 'unknown', address: '0.0.0.0:8002', checkedAt: '2026-08-04T00:00:00Z' },
  },
  restartRequired: false,
  restartServices: [],
  apiKey: 'must-not-leak',
}

describe('system settings administrator API', () => {
  beforeEach(() => {
    vi.spyOn(http, 'get')
    vi.spyOn(http, 'put')
  })

  it('loads and parses only the safe settings document', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: { code: 0, msg: '', data: payload }, config: {} })

    const result = await getSystemSettings()

    expect(http.get).toHaveBeenCalledWith('/admin/companion/system-settings', { params: {} })
    expect(result.health.xiaozhi.status).toBe('available')
    expect(result.modelOptions.LLM[0].id).toBe('llm-1')
    expect(result).not.toHaveProperty('apiKey')
  })

  it('saves one document and parses the server-refreshed result', async () => {
    vi.mocked(http.put).mockResolvedValue({ data: { code: 0, msg: '', data: { ...payload, restartRequired: true, restartServices: ['xiaozhi'] } }, config: {} })

    const result = await saveSystemSettings(input)

    expect(http.put).toHaveBeenCalledWith('/admin/companion/system-settings', input)
    expect(result.restartRequired).toBe(true)
    expect(result.restartServices).toEqual(['xiaozhi'])
  })

  it('loads settings when the default TTS voice has not been selected yet', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: { code: 0, msg: '', data: { ...payload, defaultTtsVoiceId: null } }, config: {} })

    const result = await getSystemSettings()

    expect(result.defaultTtsVoiceId).toBeUndefined()
    expect(result.voices).toHaveLength(1)
  })

  it.each([
    { ...payload, xiaozhiListenPort: '8000' },
    { ...payload, health: { ...payload.health, xiaozhi: { ...payload.health.xiaozhi, status: 'healthy' } } },
    { ...payload, modelOptions: { LLM: [{ name: '缺少 ID', type: 'LLM' }] } },
    { ...payload, health: { ...payload.health, ota: { ...payload.health.ota, checkedAt: '' } } },
  ])('rejects malformed settings payload %#', async (data) => {
    vi.mocked(http.get).mockResolvedValue({ data: { code: 0, msg: '', data }, config: {} })

    await expect(getSystemSettings()).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })
})
