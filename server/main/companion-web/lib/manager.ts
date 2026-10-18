export interface WebAgent {
  id: string
  agentName?: string
  name?: string
  activeVersionNo?: number | null
  ttsModelId?: string | null
  ttsModelName?: string | null
  ttsVoiceId?: string | null
  ttsVoiceName?: string | null
}

export interface WebVoice {
  id: string
  name: string
  ttsModelId?: string | null
  voiceDemo?: string | null
  languages?: string | null
}

export interface ConversationSession {
  conversationId: string
  agentId: string
  agentVersion: number
  streamUrl: string
  runtimeToken: string
  expiresAt: string
  inputModes: string[]
  outputModes: string[]
  publicMetadata?: Record<string, string>
}

export async function managerJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, { ...init, cache: 'no-store' })
  const payload = await response.json().catch(() => ({})) as { data?: T; msg?: string; error?: string }
  if (!response.ok) throw new Error(payload.error || payload.msg || `请求失败（${response.status}）`)
  return (payload.data ?? payload) as T
}
