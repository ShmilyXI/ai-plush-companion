import { describe, expect, it } from 'vitest'
import { parseBootstrapMessage, selectAgentVoice } from './bootstrap'

describe('companion web bootstrap', () => {
  it('accepts a bootstrap code only from the configured console origin', () => {
    expect(parseBootstrapMessage(
      { type: 'companion.bootstrap', code: 'code-1' },
      'https://console.example.com',
      'https://console.example.com',
    )).toEqual({ code: 'code-1' })
    expect(parseBootstrapMessage(
      { type: 'companion.bootstrap', code: 'code-1' },
      'https://evil.example.com',
      'https://console.example.com',
    )).toBeNull()
  })

  it('selects an active agent and its configured voice', () => {
    expect(selectAgentVoice(
      { id: 'agent-1', activeVersionNo: 4, ttsModelId: 'tts-1', ttsVoiceId: 'voice-1' },
      [{ id: 'voice-1', ttsModelId: 'tts-1' }, { id: 'voice-2', ttsModelId: 'tts-1' }],
    )).toEqual({ agentId: 'agent-1', voiceId: 'voice-1' })
  })
})
