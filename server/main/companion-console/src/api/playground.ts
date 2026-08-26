import { ApiError, type ApiResult } from './http'
import http from './http'

export interface PlaygroundConversationSession {
  conversationId: string
  agentId: string
  agentVersion: number
  streamUrl: string
  runtimeToken: string
  expiresAt: string
  inputModes: string[]
  outputModes: string[]
  publicMetadata: Record<string, string>
}

export interface PlaygroundConversationEvent {
  type: string
  conversation_id: string
  turn_id: string | null
  sequence: number
  occurred_at: number
  details: Record<string, unknown>
}

export interface PlaygroundConversationClient {
  socket: WebSocket
  sendText: (requestId: string, text: string) => void
  sendAudio: (requestId: string, pcm: Uint8Array, durationMs: number) => void
  cancel: (turnId: string) => void
  close: () => void
}

interface RequestOptions { signal?: AbortSignal }
interface ConnectionOptions {
  onEvent: (event: PlaygroundConversationEvent) => void
  onOpen?: () => void
  onClose?: (event: CloseEvent) => void
  onError?: (error: Error) => void
}

function unwrap<T>(response: { data: ApiResult<T> }): T {
  if (!response.data || response.data.code !== 0) {
    throw new ApiError(response.data?.code ?? 500, response.data?.msg || '请求失败', response.data?.data)
  }
  return response.data.data
}

export async function createPlaygroundConversation(input: {
  agentId: string
  voiceId?: string
  modelOverrides?: Record<string, string>
}, options?: RequestOptions) {
  const body = {
    agentId: input.agentId,
    inputModes: ['text', 'audio'],
    outputModes: ['text', 'audio'],
    ...(input.voiceId ? { voiceId: input.voiceId } : {}),
    ...(input.modelOverrides ? { modelOverrides: input.modelOverrides } : {}),
  }
  const response = await http.post<ApiResult<PlaygroundConversationSession>>(
    '/api/v1/conversations', body, options?.signal ? { signal: options.signal } : undefined,
  )
  return unwrap(response)
}

function parseEvent(value: unknown): PlaygroundConversationEvent {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('对话事件格式错误')
  const event = value as Record<string, unknown>
  if (typeof event.type !== 'string') throw new Error('对话事件格式错误')
  return {
    type: event.type,
    conversation_id: typeof event.conversation_id === 'string' ? event.conversation_id : '',
    turn_id: typeof event.turn_id === 'string' ? event.turn_id : null,
    sequence: typeof event.sequence === 'number' ? event.sequence : 0,
    occurred_at: typeof event.occurred_at === 'number' ? event.occurred_at : Date.now(),
    details: event.details && typeof event.details === 'object' && !Array.isArray(event.details)
      ? event.details as Record<string, unknown> : {},
  }
}

export function connectPlaygroundConversation(
  session: PlaygroundConversationSession,
  options: ConnectionOptions,
): PlaygroundConversationClient {
  const socket = new WebSocket(session.streamUrl, [`bearer.${session.runtimeToken}`])

  socket.addEventListener('open', () => options.onOpen?.())
  socket.addEventListener('close', (event) => options.onClose?.(event))
  socket.addEventListener('error', () => options.onError?.(new Error('对话连接失败')))
  socket.addEventListener('message', (message) => {
    if (typeof message.data !== 'string') return
    try {
      options.onEvent(parseEvent(JSON.parse(message.data)))
    } catch (error) {
      options.onError?.(error instanceof Error ? error : new Error('对话事件格式错误'))
    }
  })

  const send = (payload: Record<string, unknown>) => {
    if (socket.readyState !== WebSocket.OPEN) throw new Error('对话连接尚未就绪')
    socket.send(JSON.stringify(payload))
  }

  return {
    socket,
    sendText(requestId, text) {
      send({ type: 'turn.text', request_id: requestId, text })
    },
    sendAudio(requestId, pcm, durationMs) {
      send({ type: 'turn.audio.start', request_id: requestId, duration_ms: durationMs })
      socket.send(pcm.slice().buffer)
      send({ type: 'turn.audio.end' })
    },
    cancel(turnId) {
      send({ type: 'turn.cancel', turn_id: turnId })
    },
    close() {
      socket.close()
    },
  }
}
