import type { AxiosResponse } from 'axios'

import http, {
  apiBaseUrl,
  ApiError,
  currentAuthorizationHeader,
  notifyUnauthorizedForToken,
  type ApiResult,
} from './http'
import { ApiProtocolError } from './devices'

export type DebugLogCategory = 'conversation' | 'model_tool' | 'audio' | 'device'
export type DebugLogLevel = 'debug' | 'info' | 'warning' | 'error'

export interface DebugLogEvent {
  cursor: string
  deviceId: string
  occurredAt: number
  receivedAt: number
  sessionId: string | null
  sentenceId: string | null
  category: DebugLogCategory
  eventType: string
  level: DebugLogLevel
  summary: string
  details: Record<string, unknown>
  durationMs: number | null
}

export interface DeviceDebugLogHistory {
  events: DebugLogEvent[]
  lastCursor: string
}

interface RequestOptions {
  signal?: AbortSignal
}

interface StreamOptions extends RequestOptions {
  onOpen?: () => void
  onEvent: (event: DebugLogEvent) => void
}

const categories: DebugLogCategory[] = ['conversation', 'model_tool', 'audio', 'device']
const levels: DebugLogLevel[] = ['debug', 'info', 'warning', 'error']
const eventTypePattern = /^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$/
const decimalPattern = /^(?:0|[1-9]\d*)$/

function isRecord(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

function requestConfig(options?: RequestOptions) {
  return options?.signal ? { signal: options.signal } : undefined
}

function encodedId(id: string) {
  return encodeURIComponent(id)
}

function unwrap<T>(response: AxiosResponse<ApiResult<T>>): T {
  const result: unknown = response.data
  if (!isRecord(result) || typeof result.code !== 'number' || typeof result.msg !== 'string' || !('data' in result)) {
    throw new ApiProtocolError('响应数据格式错误', result, response.config)
  }
  if (result.code !== 0) {
    throw new ApiError(result.code, result.msg || '请求失败', result.data, response.config)
  }
  return result.data as T
}

function safeNonNegativeNumber(value: unknown): number | null {
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) && value >= 0 ? value : null
  }
  if (typeof value !== 'string' || !decimalPattern.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}

function nullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function parseDebugLogEvent(value: unknown, fallbackCursor?: string): DebugLogEvent {
  if (!isRecord(value)) throw new Error('实时日志事件格式错误')
  const cursor = 'cursor' in value ? value.cursor : fallbackCursor
  const occurredAt = safeNonNegativeNumber(value.occurredAt)
  const receivedAt = safeNonNegativeNumber(value.receivedAt)
  const durationMs = value.durationMs === null ? null : safeNonNegativeNumber(value.durationMs)
  if (
    typeof cursor !== 'string'
    || cursor.length === 0
    || typeof value.deviceId !== 'string'
    || value.deviceId.length === 0
    || occurredAt === null
    || receivedAt === null
    || !('sessionId' in value)
    || !nullableString(value.sessionId)
    || !('sentenceId' in value)
    || !nullableString(value.sentenceId)
    || !categories.includes(value.category as DebugLogCategory)
    || typeof value.eventType !== 'string'
    || !eventTypePattern.test(value.eventType)
    || !levels.includes(value.level as DebugLogLevel)
    || typeof value.summary !== 'string'
    || !isRecord(value.details)
    || !('durationMs' in value)
    || durationMs === null && value.durationMs !== null
  ) {
    throw new Error('实时日志事件格式错误')
  }
  return {
    cursor,
    deviceId: value.deviceId,
    occurredAt,
    receivedAt,
    sessionId: value.sessionId,
    sentenceId: value.sentenceId,
    category: value.category as DebugLogCategory,
    eventType: value.eventType,
    level: value.level as DebugLogLevel,
    summary: value.summary,
    details: value.details,
    durationMs,
  }
}

function parseHistory(value: unknown, response: AxiosResponse): DeviceDebugLogHistory {
  if (!isRecord(value) || !Array.isArray(value.events) || typeof value.lastCursor !== 'string') {
    throw new ApiProtocolError('设备调试日志历史格式错误', value, response.config)
  }
  try {
    return {
      events: value.events.map((event) => parseDebugLogEvent(event)),
      lastCursor: value.lastCursor,
    }
  } catch {
    throw new ApiProtocolError('设备调试日志历史字段错误', value, response.config)
  }
}

export async function getDeviceDebugLogHistory(deviceId: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(
    `/companion/devices/${encodedId(deviceId)}/debug-logs`,
    requestConfig(options),
  )
  return parseHistory(unwrap(response), response)
}

function dispatchSseBlock(block: string, lastId: string | undefined, onEvent: (event: DebugLogEvent) => void) {
  const lines = block.split('\n')
  let eventName = ''
  let id: string | undefined
  const data: string[] = []
  let hasField = false
  for (const line of lines) {
    if (line.startsWith(':')) continue
    if (line === '') continue
    hasField = true
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let fieldValue = colon === -1 ? '' : line.slice(colon + 1)
    if (fieldValue.startsWith(' ')) fieldValue = fieldValue.slice(1)
    if (field === 'event') eventName = fieldValue
    else if (field === 'id') id = fieldValue
    else if (field === 'data') data.push(fieldValue)
  }
  const nextId = id ?? lastId
  if (!hasField || eventName !== 'debug-log') return nextId
  if (data.length === 0 || data.every((line) => line.length === 0)) {
    throw new Error('实时日志事件格式错误')
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(data.join('\n'))
  } catch {
    throw new Error('实时日志事件格式错误')
  }
  onEvent(parseDebugLogEvent(parsed, nextId))
  return nextId
}

function streamUrl(deviceId: string, after: string) {
  const base = apiBaseUrl().replace(/\/+$/, '')
  return `${base}/companion/devices/${encodedId(deviceId)}/debug-logs/stream?after=${encodeURIComponent(after)}`
}

export async function streamDeviceDebugLogs(deviceId: string, after: string, options: StreamOptions) {
  const authorization = currentAuthorizationHeader()
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  if (authorization) headers.Authorization = authorization
  const response = await fetch(streamUrl(deviceId, after), { headers, signal: options.signal })
  if (!response.ok) {
    if (response.status === 401) notifyUnauthorizedForToken(authorization)
    let result: unknown
    try {
      result = await response.json()
    } catch {
      result = null
    }
    if (isRecord(result) && typeof result.code === 'number' && typeof result.msg === 'string' && 'data' in result) {
      throw new ApiError(result.code, result.msg || `实时日志连接失败 (${response.status})`, result.data)
    }
    if (response.status === 403) throw new ApiError(403, '没有管理员权限', result)
    throw new Error(`实时日志连接失败 (${response.status})`)
  }
  if (!response.body) {
    throw new Error(`实时日志连接失败 (${response.status})`)
  }
  options.onOpen?.()

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let pendingCarriageReturn = false
  let lastId: string | undefined
  try {
    while (true) {
      const { done, value } = await reader.read()
      let text = decoder.decode(value, { stream: !done })
      if (pendingCarriageReturn) {
        text = `\r${text}`
        pendingCarriageReturn = false
      }
      if (!done && text.endsWith('\r')) {
        text = text.slice(0, -1)
        pendingCarriageReturn = true
      }
      buffer += text.replace(/\r\n?/g, '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary !== -1) {
        lastId = dispatchSseBlock(buffer.slice(0, boundary), lastId, options.onEvent)
        buffer = buffer.slice(boundary + 2)
        boundary = buffer.indexOf('\n\n')
      }
      if (done) break
    }
    if (pendingCarriageReturn) buffer += '\n'
    if (buffer.length > 0) dispatchSseBlock(buffer, lastId, options.onEvent)
  } catch (error) {
    try {
      await reader.cancel()
    } catch {
      // Preserve the original stream or consumer error.
    }
    throw error
  } finally {
    reader.releaseLock()
  }
}
