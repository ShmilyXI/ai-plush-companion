import { afterEach, describe, expect, it, vi } from 'vitest'

import http, { configureAuthBridge } from './http'
import {
  getDeviceDebugLogHistory,
  streamDeviceDebugLogs,
  type DebugLogEvent,
} from './deviceDebugLogs'

const validEvent = {
  cursor: '10-0',
  deviceId: 'device-a',
  occurredAt: '1800000000000',
  receivedAt: 1800000000001,
  sessionId: null,
  sentenceId: 'sentence-a',
  category: 'conversation',
  eventType: 'conversation.started',
  level: 'info',
  summary: 'started',
  details: { source: 'wake-word' },
  durationMs: '12',
}

function responseBody(chunks: string[]) {
  const encoder = new TextEncoder()
  return new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)))
      controller.close()
    },
  })
}

function fetchResponse(chunks: string[], init: ResponseInit = {}) {
  return new Response(responseBody(chunks), { status: 200, ...init })
}

function trackedResponse(readResults: Array<ReadableStreamReadResult<Uint8Array> | Error>) {
  const cancel = vi.fn().mockResolvedValue(undefined)
  const releaseLock = vi.fn()
  const read = vi.fn(async () => {
    const result = readResults.shift()
    if (result instanceof Error) return await Promise.reject(result)
    return result ?? { done: true as const, value: undefined }
  })
  const body = {
    getReader: () => ({ read, cancel, releaseLock }),
  } as unknown as ReadableStream<Uint8Array>
  return {
    response: { ok: true, status: 200, body } as Response,
    cancel,
    releaseLock,
  }
}

function rejectingResponse(error: Error) {
  const cancel = vi.fn().mockResolvedValue(undefined)
  const releaseLock = vi.fn()
  const body = {
    getReader: () => ({ read: () => Promise.reject(error), cancel, releaseLock }),
  } as unknown as ReadableStream<Uint8Array>
  return { response: { ok: true, status: 200, body } as Response, cancel, releaseLock }
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllEnvs()
  configureAuthBridge({ getToken: () => null, onUnauthorized: () => undefined })
})

describe('device debug log history', () => {
  it('gets an encoded device path and normalizes safe Java Long strings', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({
      data: { code: 0, msg: 'success', data: { events: [validEvent], lastCursor: '10-0' } },
    })

    await expect(getDeviceDebugLogHistory('device /?#%')).resolves.toEqual({
      events: [{ ...validEvent, occurredAt: 1_800_000_000_000, durationMs: 12 }],
      lastCursor: '10-0',
    })
    expect(http.get).toHaveBeenCalledWith(
      `/companion/devices/${encodeURIComponent('device /?#%')}/debug-logs`,
      undefined,
    )
  })

  it.each([
    null,
    {},
    { events: {}, lastCursor: '10-0' },
    { events: [validEvent], lastCursor: 10 },
    { events: [{ ...validEvent, category: 'network' }], lastCursor: '10-0' },
    { events: [{ ...validEvent, level: 'fatal' }], lastCursor: '10-0' },
    { events: [{ ...validEvent, eventType: 'Conversation Started' }], lastCursor: '10-0' },
    { events: [{ ...validEvent, eventType: 'conversation.bad-segment' }], lastCursor: '10-0' },
    { events: [{ ...validEvent, details: [] }], lastCursor: '10-0' },
    { events: [{ ...validEvent, occurredAt: '9007199254740992' }], lastCursor: '10-0' },
    { events: [{ ...validEvent, durationMs: -1 }], lastCursor: '10-0' },
    { events: [{ ...validEvent, durationMs: 'NaN' }], lastCursor: '10-0' },
    { events: [{ ...validEvent, summary: null }], lastCursor: '10-0' },
    { events: [{ ...validEvent, sentenceId: 7 }], lastCursor: '10-0' },
    { events: [{ ...validEvent, sessionId: undefined }], lastCursor: '10-0' },
    { events: [{ ...validEvent, durationMs: undefined }], lastCursor: '10-0' },
  ])('rejects malformed history payload %#', async (data) => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data } })

    await expect(getDeviceDebugLogHistory('device-a')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })
})

describe('authenticated device debug log stream', () => {
  it('sends bearer auth in headers, encodes URL values, and never puts the token in the query', async () => {
    configureAuthBridge({ getToken: () => 'secret token', onUnauthorized: () => undefined })
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(fetchResponse([]))

    await streamDeviceDebugLogs('device /?#%', '1 /?#%', { onEvent: vi.fn() })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`/zixuan/companion/devices/${encodeURIComponent('device /?#%')}/debug-logs/stream?after=${encodeURIComponent('1 /?#%')}`)
    expect(init).toMatchObject({ headers: { Accept: 'text/event-stream', Authorization: 'Bearer secret token' } })
    expect(String(url)).not.toContain('secret token')
  })

  it('omits authorization without a current token and calls onOpen after response confirmation', async () => {
    const onOpen = vi.fn()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(fetchResponse([]))

    await streamDeviceDebugLogs('device-a', '0-0', { onOpen, onEvent: vi.fn() })

    expect(onOpen).toHaveBeenCalledOnce()
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ headers: { Accept: 'text/event-stream' } })
    expect((fetchMock.mock.calls[0][1]?.headers as Record<string, string>).Authorization).toBeUndefined()
  })

  it('notifies authentication expiry for the token used by an HTTP 401 stream request', async () => {
    const onUnauthorized = vi.fn()
    configureAuthBridge({ getToken: () => 'current-token', onUnauthorized })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 401 }))

    await expect(streamDeviceDebugLogs('device-a', '0-0', { onEvent: vi.fn() }))
      .rejects.toThrow('实时日志连接失败 (401)')

    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('does not clear a newer session when an older stream request receives HTTP 401', async () => {
    let currentToken: string | null = 'old-token'
    const onUnauthorized = vi.fn()
    configureAuthBridge({ getToken: () => currentToken, onUnauthorized })
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      currentToken = 'new-token'
      return new Response(null, { status: 401 })
    })

    await expect(streamDeviceDebugLogs('device-a', '0-0', { onEvent: vi.fn() }))
      .rejects.toThrow('实时日志连接失败 (401)')

    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('notifies authentication expiry for an unauthenticated HTTP 401 request', async () => {
    const onUnauthorized = vi.fn()
    configureAuthBridge({ getToken: () => null, onUnauthorized })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 401 }))

    await expect(streamDeviceDebugLogs('device-a', '0-0', { onEvent: vi.fn() }))
      .rejects.toThrow('实时日志连接失败 (401)')

    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('maps structured non-success results to ApiError', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(
      JSON.stringify({ code: 10206, msg: '设备日志不可用', data: { reason: 'offline' } }),
      { status: 409, headers: { 'Content-Type': 'application/json' } },
    ))

    await expect(streamDeviceDebugLogs('device-a', '0-0', { onEvent: vi.fn() }))
      .rejects.toMatchObject({
        name: 'ApiError',
        code: 10206,
        message: '设备日志不可用',
        data: { reason: 'offline' },
      })
  })

  it('maps a bare HTTP 403 to the shared administrator permission error', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('not-json', { status: 403 }))

    await expect(streamDeviceDebugLogs('device-a', '0-0', { onEvent: vi.fn() }))
      .rejects.toEqual(expect.objectContaining({
        name: 'ApiError',
        code: 403,
        message: '没有管理员权限',
      }))
  })

  it('parses split chunks, CRLF, comments, multiple data lines, id fallback, and EOF events', async () => {
    const jsonCursorWins = JSON.stringify({ ...validEvent, cursor: 'json-cursor' })
    const fallback = JSON.stringify({ ...validEvent, cursor: undefined, summary: 'fallback' })
    const multiline = JSON.stringify({ ...validEvent, cursor: 'multi', summary: 'two lines' })
    const splitAt = multiline.indexOf(',"summary"')
    const onEvent = vi.fn<(event: DebugLogEvent) => void>()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(fetchResponse([
      ': heartbeat\r\n\r\nevent: ignored\r\ndata: {}\r\n\r\nid: sse-id\r\nevent: debug-log\r\ndata: ',
      jsonCursorWins.slice(0, 20),
      `${jsonCursorWins.slice(20)}\r\n\r\nid: fallback-id\nevent: debug-log\ndata: ${fallback}\n\nevent: debug-log\ndata: ${multiline.slice(0, splitAt)}`,
      `\ndata: ${multiline.slice(splitAt)}\n`,
    ]))

    await streamDeviceDebugLogs('device-a', '0-0', { onEvent })

    expect(onEvent.mock.calls.map(([event]) => [event.cursor, event.summary])).toEqual([
      ['json-cursor', 'started'],
      ['fallback-id', 'fallback'],
      ['multi', 'two lines'],
    ])
  })

  it('handles a CRLF delimiter split across chunks without creating an empty event', async () => {
    const onEvent = vi.fn<(event: DebugLogEvent) => void>()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(fetchResponse([
      `event: debug-log\r\ndata: ${JSON.stringify(validEvent)}\r`,
      '\n\r',
      '\n',
    ]))

    await streamDeviceDebugLogs('device-a', '0-0', { onEvent })

    expect(onEvent).toHaveBeenCalledOnce()
    expect(onEvent).toHaveBeenCalledWith(expect.objectContaining({ cursor: '10-0' }))
  })

  it('handles CRLF split across chunks between ordinary SSE fields', async () => {
    const onEvent = vi.fn<(event: DebugLogEvent) => void>()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(fetchResponse([
      'event: debug-log\r',
      `\ndata: ${JSON.stringify(validEvent)}\r\n\r\n`,
    ]))

    await streamDeviceDebugLogs('device-a', '0-0', { onEvent })

    expect(onEvent).toHaveBeenCalledOnce()
  })

  it('inherits the last SSE id when the next debug-log block omits id and JSON cursor', async () => {
    const eventWithoutCursor = JSON.stringify({ ...validEvent, cursor: undefined })
    const onEvent = vi.fn<(event: DebugLogEvent) => void>()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(fetchResponse([
      `id: inherited-id\n\nevent: debug-log\ndata: ${eventWithoutCursor}\n\n`,
    ]))

    await streamDeviceDebugLogs('device-a', '0-0', { onEvent })

    expect(onEvent).toHaveBeenCalledWith(expect.objectContaining({ cursor: 'inherited-id' }))
  })

  it('removes every trailing slash from the configured API base', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '/custom-api///')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(fetchResponse([]))

    await streamDeviceDebugLogs('device-a', '0-0', { onEvent: vi.fn() })

    expect(fetchMock.mock.calls[0][0]).toBe('/custom-api/companion/devices/device-a/debug-logs/stream?after=0-0')
  })

  it('rejects non-success responses and responses without readable bodies before onOpen', async () => {
    const onOpen = vi.fn()
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockResolvedValueOnce({ ok: true, status: 200, body: null } as Response)

    await expect(streamDeviceDebugLogs('device-a', '0-0', { onOpen, onEvent: vi.fn() }))
      .rejects.toMatchObject({ name: 'ApiError', code: 403, message: '没有管理员权限' })
    await expect(streamDeviceDebugLogs('device-a', '0-0', { onOpen, onEvent: vi.fn() }))
      .rejects.toThrow('实时日志连接失败 (200)')
    expect(onOpen).not.toHaveBeenCalled()
  })

  it.each([
    'event: debug-log\ndata: {bad json}\n\n',
    `event: debug-log\ndata: ${JSON.stringify({ ...validEvent, category: 'unknown' })}\n\n`,
    'event: debug-log\ndata:\n\n',
    `id: fallback-id\nevent: debug-log\ndata: ${JSON.stringify({ ...validEvent, cursor: '' })}\n\n`,
  ])('rejects malformed debug-log events instead of dropping them %#', async (payload) => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(fetchResponse([payload]))

    await expect(streamDeviceDebugLogs('device-a', '0-0', { onEvent: vi.fn() }))
      .rejects.toThrow(/实时日志事件格式错误/)
  })

  it('passes the abort signal to fetch and propagates AbortError', async () => {
    const controller = new AbortController()
    const abortError = new DOMException('Aborted', 'AbortError')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockRejectedValue(abortError)

    const promise = streamDeviceDebugLogs('device-a', '0-0', {
      signal: controller.signal,
      onEvent: vi.fn(),
    })

    await expect(promise).rejects.toBe(abortError)
    expect(fetchMock.mock.calls[0][1]?.signal).toBe(controller.signal)
  })

  it('cancels and releases the reader when parsing fails before EOF', async () => {
    const encoder = new TextEncoder()
    const stream = trackedResponse([
      { done: false, value: encoder.encode('event: debug-log\ndata: {bad json}\n\n') },
    ])
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(stream.response)

    await expect(streamDeviceDebugLogs('device-a', '0-0', { onEvent: vi.fn() }))
      .rejects.toThrow('实时日志事件格式错误')

    expect(stream.cancel).toHaveBeenCalledOnce()
    expect(stream.releaseLock).toHaveBeenCalledOnce()
  })

  it('cancels and releases the reader when the event consumer throws', async () => {
    const encoder = new TextEncoder()
    const consumerError = new Error('consumer failed')
    const stream = trackedResponse([
      { done: false, value: encoder.encode(`event: debug-log\ndata: ${JSON.stringify(validEvent)}\n\n`) },
    ])
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(stream.response)

    await expect(streamDeviceDebugLogs('device-a', '0-0', {
      onEvent: () => { throw consumerError },
    })).rejects.toBe(consumerError)

    expect(stream.cancel).toHaveBeenCalledOnce()
    expect(stream.releaseLock).toHaveBeenCalledOnce()
  })

  it('does not let reader cancellation errors replace the stream error', async () => {
    const encoder = new TextEncoder()
    const stream = trackedResponse([
      { done: false, value: encoder.encode('event: debug-log\ndata: {bad json}\n\n') },
    ])
    stream.cancel.mockRejectedValue(new Error('cancel failed'))
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(stream.response)

    await expect(streamDeviceDebugLogs('device-a', '0-0', { onEvent: vi.fn() }))
      .rejects.toThrow('实时日志事件格式错误')
    expect(stream.releaseLock).toHaveBeenCalledOnce()
  })

  it('releases without cancelling after normal EOF', async () => {
    const stream = trackedResponse([{ done: true, value: undefined }])
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(stream.response)

    await streamDeviceDebugLogs('device-a', '0-0', { onEvent: vi.fn() })

    expect(stream.cancel).not.toHaveBeenCalled()
    expect(stream.releaseLock).toHaveBeenCalledOnce()
  })

  it('cancels, releases, and propagates AbortError from an established stream', async () => {
    const abortError = new DOMException('Aborted', 'AbortError')
    const stream = rejectingResponse(abortError)
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(stream.response)

    await expect(streamDeviceDebugLogs('device-a', '0-0', { onEvent: vi.fn() }))
      .rejects.toBe(abortError)

    expect(stream.cancel).toHaveBeenCalledOnce()
    expect(stream.releaseLock).toHaveBeenCalledOnce()
  })
})
