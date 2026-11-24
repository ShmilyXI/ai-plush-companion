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
  /** 所属语音合成模型的名字，角色设置里按模型分组显示。 */
  ttsModelName?: string | null
  /** 场景分组，例如 通用场景、角色扮演。没有时只按模型分组。 */
  scene?: string | null
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

/** manager-api 的业务错误码：今天的对话次数已经用完。 */
export const DAILY_TURN_LIMIT_REACHED = 10212

/** 带业务错误码的请求失败，调用方可以按错误码决定怎么处理。 */
export class ManagerError extends Error {
  constructor(message: string, readonly status: number, readonly code?: number) {
    super(message)
    this.name = 'ManagerError'
  }
}

export async function managerJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, { ...init, cache: 'no-store' })
  const payload = await response.json().catch(() => ({})) as { data?: T; msg?: string; error?: string; code?: unknown }
  if (!response.ok) {
    throw new ManagerError(payload.error || payload.msg || `请求失败（${response.status}）`, response.status,
      typeof payload.code === 'number' ? payload.code : undefined)
  }
  return (payload.data ?? payload) as T
}
