import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PlaygroundPage } from './PlaygroundPage'
import { playgroundStorageKey } from './playgroundStorage'
import type { PlaygroundSession } from './playgroundTypes'

vi.mock('../../api/profiles', () => ({ listProfiles: vi.fn(), listProfileModelOptions: vi.fn() }))
vi.mock('../../api/playground', () => ({ createPlaygroundConversation: vi.fn(), connectPlaygroundConversation: vi.fn() }))

import { listProfileModelOptions, listProfiles } from '../../api/profiles'
import { connectPlaygroundConversation, createPlaygroundConversation } from '../../api/playground'

describe('PlaygroundPage', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(listProfiles).mockResolvedValue([{ id: 'p1', name: '小夏', relationMode: 'friend', userAddress: '', personality: '', systemPrompt: '', screenExpressionEnabled: true, cameraPreferenceEnabled: false, templateId: null, llmModelId: null, llmModelName: null, ttsModelId: null, ttsModelName: null, ttsVoiceId: null, ttsVoiceName: null, ttsLanguage: null, createdAt: null, updatedAt: null, models: [], effectiveModels: [], skills: [] }])
    vi.mocked(listProfileModelOptions).mockResolvedValue([])
    vi.mocked(createPlaygroundConversation).mockResolvedValue({
      conversationId: 'conversation-a', agentId: 'p1', agentVersion: 3,
      streamUrl: 'ws://runtime.test/stream', runtimeToken: 'runtime-token', expiresAt: '2026-08-26T12:00:00Z',
      inputModes: ['text', 'audio'], outputModes: ['text', 'audio'],
      publicMetadata: { agent_name: '小夏', tts_voice_id: '' },
    })
    vi.mocked(connectPlaygroundConversation).mockReturnValue({
      socket: { readyState: WebSocket.OPEN } as WebSocket,
      sendText: vi.fn(), sendAudio: vi.fn(), cancel: vi.fn(), close: vi.fn(),
    })
  })
  it('renders the fixed three-column controls', async () => {
    render(<PlaygroundPage />)
    expect(await screen.findByText('历史对话')).toBeVisible()
    expect(screen.getByText('运行配置')).toBeVisible()
    expect(screen.getByRole('button', { name: '创建对话会话' })).toBeVisible()
  })
  it('shows ASR transcript and TTS audio results', async () => {
    const session = { id: 'result-session', title: '结果测试', createdAt: '2026-08-23T00:00:00Z', playgroundSessionId: null, snapshot: { profileId: 'p1', profileName: '小夏', models: {}, ttsVoiceId: null, skills: [], virtualDevice: { width: 240, height: 240, depth: 8, orientation: 'square', screen: true, camera: true, microphone: true, activitySensor: true } }, messages: [], events: [
      { sessionId: '', sequence: 1, capability: 'asr', stage: 'recognition', status: 'completed', startedAt: 0, finishedAt: 0, durationMs: 0, inputSummary: '音频输入', outputSummary: '你好', error: null, details: { transcript: '你好' } },
      { sessionId: '', sequence: 2, capability: 'tts', stage: 'synthesis', status: 'completed', startedAt: 0, finishedAt: 0, durationMs: 0, inputSummary: '你好', outputSummary: '音频已生成', error: null, details: { text: '你好，这是试听。', audioDataUrl: 'data:audio/wav;base64,UklGRg==' } },
    ], screenState: {}, memories: [] } satisfies PlaygroundSession
    localStorage.setItem(playgroundStorageKey, JSON.stringify({ version: 1, sessions: [session], activeSessionId: session.id, updatedAt: session.createdAt }))
    const { container } = render(<PlaygroundPage />)
    await screen.findByRole('button', { name: /^结果测试/ })
    expect(await screen.findByText(/识别文字：你好/)).toBeVisible()
    expect(await screen.findByText('语音内容：你好，这是试听。')).toBeVisible()
    expect(container.querySelector('audio')).not.toBeNull()
  })

  it('starts the playground through the public conversation session', async () => {
    render(<PlaygroundPage />)
    await screen.findByRole('button', { name: '创建对话会话' })

    await screen.getByRole('button', { name: '创建对话会话' }).click()

    expect(createPlaygroundConversation).toHaveBeenCalledWith({
      agentId: 'p1', voiceId: undefined, modelOverrides: {},
    })
    expect(connectPlaygroundConversation).toHaveBeenCalledWith(
      expect.objectContaining({ conversationId: 'conversation-a' }),
      expect.objectContaining({ onEvent: expect.any(Function) }),
    )
  })
})
