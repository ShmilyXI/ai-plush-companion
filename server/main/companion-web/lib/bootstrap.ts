export interface AgentSummary {
  id: string
  activeVersionNo?: number | null
  ttsModelId?: string | null
  ttsVoiceId?: string | null
}

export interface VoiceSummary {
  id: string
  ttsModelId?: string | null
}

export function parseBootstrapMessage(value: unknown, source: string, expectedOrigin: string) {
  if (source !== expectedOrigin || !value || typeof value !== 'object' || Array.isArray(value)) return null
  const message = value as Record<string, unknown>
  if (message.type !== 'companion.bootstrap' || typeof message.code !== 'string' || !message.code.trim()) return null
  return { code: message.code.trim() }
}

export function selectAgentVoice(agent: AgentSummary, voices: VoiceSummary[]) {
  if (!agent.id || !agent.activeVersionNo || agent.activeVersionNo <= 0) throw new Error('角色没有已发布版本')
  const requested = agent.ttsVoiceId?.trim()
  const voice = requested ? voices.find((item) => item.id === requested) : undefined
  if (requested && !voice) throw new Error('角色默认音色不可用')
  if (voice?.ttsModelId && agent.ttsModelId && voice.ttsModelId !== agent.ttsModelId) {
    throw new Error('角色默认音色与 TTS 模型不匹配')
  }
  return { agentId: agent.id, voiceId: voice?.id }
}
