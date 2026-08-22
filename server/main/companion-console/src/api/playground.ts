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
  const raw = value as Record<string, unknown>
  const item = raw.data && typeof raw.data === 'object' ? raw.data as Record<string, unknown> : raw
  const sequence = Number(item.sequence ?? item.id ?? 0)
  const capability = String(item.capability ?? item.category ?? 'runtime')
  const stage = String(item.stage ?? item.eventType ?? 'event')
  const status = String(item.status ?? (item.error ? 'failed' : 'completed')) as PlaygroundEvent['status']
  if (!Number.isFinite(sequence)) throw new Error('操练事件格式错误')
  return { sessionId: typeof item.sessionId === 'string' ? item.sessionId : typeof item.session_id === 'string' ? item.session_id : '', sequence, capability, stage, status, startedAt: Number(item.startedAt ?? item.started_at ?? Date.now()), finishedAt: item.finishedAt === null || item.finished_at === null ? null : Number(item.finishedAt ?? item.finished_at ?? Date.now()), durationMs: item.durationMs === null || item.duration_ms === null ? null : Number(item.durationMs ?? item.duration_ms ?? 0), inputSummary: String(item.inputSummary ?? item.input_summary ?? ''), outputSummary: String(item.outputSummary ?? item.output_summary ?? ''), error: item.error === null || item.error === undefined ? null : String(item.error) }
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
