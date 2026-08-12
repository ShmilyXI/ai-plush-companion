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

afterEach(() => {
  vi.restoreAllMocks()
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
    expect(url).toBe(`/xiaozhi/companion/devices/${encodeURIComponent('device /?#%')}/debug-logs/stream?after=${encodeURIComponent('1 /?#%')}`)
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

  it('rejects non-success responses and responses without readable bodies before onOpen', async () => {
    const onOpen = vi.fn()
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockResolvedValueOnce({ ok: true, status: 200, body: null } as Response)

    await expect(streamDeviceDebugLogs('device-a', '0-0', { onOpen, onEvent: vi.fn() }))
      .rejects.toThrow('实时日志连接失败 (403)')
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
})
