import { ApiError, apiBaseUrl, currentAuthorizationHeader, notifyUnauthorizedForToken, type ApiResult } from './http'
import http from './http'
import type { PlaygroundEvent, PlaygroundInputKind } from '../pages/playground/playgroundTypes'

export interface PlaygroundSessionCreated { sessionId: string; snapshotVersion: number; effectiveConfig: Record<string, unknown>; eventStreamPath: string; expiresAt: string }
export interface PlaygroundInput { kind: PlaygroundInputKind; text?: string; audioRef?: string; imageRef?: string; activity?: Record<string, unknown> }
interface RequestOptions { signal?: AbortSignal }
interface StreamOptions extends RequestOptions { onEvent: (event: PlaygroundEvent) => void; onOpen?: () => void }

function unwrap<T>(response: { data: ApiResult<T> }): T {
  if (!response.data || response.data.code !== 0) throw new ApiError(response.data?.code ?? 500, response.data?.msg || '请求失败', response.data?.data)
  return response.data.data
}

export async function createPlaygroundSession(input: { profileId: string; models?: Record<string, string>; ttsVoiceId?: string; skillIds?: string[]; virtualDevice: Record<string, unknown> }, options?: RequestOptions) {
  const response = await http.post<ApiResult<PlaygroundSessionCreated>>('/companion/playground/sessions', input, options?.signal ? { signal: options.signal } : undefined)
  return unwrap(response)
}

export async function sendPlaygroundInput(sessionId: string, input: PlaygroundInput, options?: RequestOptions) {
  await http.post<ApiResult<unknown>>(`/companion/playground/sessions/${encodeURIComponent(sessionId)}/inputs`, input, options?.signal ? { signal: options.signal } : undefined)
}

function parseEvent(value: unknown): PlaygroundEvent {
  if (!value || typeof value !== 'object') throw new Error('操练事件格式错误')
  const item = value as Record<string, unknown>
  if (typeof item.sessionId !== 'string' || typeof item.sequence !== 'number' || typeof item.capability !== 'string' || typeof item.stage !== 'string' || typeof item.status !== 'string') throw new Error('操练事件格式错误')
  return { sessionId: item.sessionId, sequence: item.sequence, capability: item.capability, stage: item.stage, status: item.status as PlaygroundEvent['status'], startedAt: Number(item.startedAt), finishedAt: item.finishedAt === null ? null : Number(item.finishedAt), durationMs: item.durationMs === null ? null : Number(item.durationMs), inputSummary: String(item.inputSummary ?? ''), outputSummary: String(item.outputSummary ?? ''), error: item.error === null || item.error === undefined ? null : String(item.error) }
}

export async function streamPlaygroundEvents(sessionId: string, after: number, options: StreamOptions) {
  const authorization = currentAuthorizationHeader()
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  if (authorization) headers.Authorization = authorization
  const response = await fetch(`${apiBaseUrl().replace(/\/+$/, '')}/companion/playground/sessions/${encodeURIComponent(sessionId)}/events?after=${after}`, { headers, signal: options.signal })
  if (!response.ok) { if (response.status === 401) notifyUnauthorizedForToken(authorization); throw new Error(`操练事件连接失败 (${response.status})`) }
  if (!response.body) throw new Error('操练事件连接没有响应体')
  options.onOpen?.()
  const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''
  while (true) {
    const { done, value } = await reader.read(); buffer += decoder.decode(value, { stream: !done }).replace(/\r\n?/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2); boundary = buffer.indexOf('\n\n')
      const data = block.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n')
      if (data) options.onEvent(parseEvent(JSON.parse(data)))
    }
    if (done) break
  }
}

export async function closePlaygroundSession(sessionId: string, options?: RequestOptions) {
  await http.delete<ApiResult<unknown>>(`/companion/playground/sessions/${encodeURIComponent(sessionId)}`, options?.signal ? { signal: options.signal } : undefined)
}
