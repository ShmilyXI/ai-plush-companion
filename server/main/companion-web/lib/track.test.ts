import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushTracking, track } from './track'

function sentEvents(call: unknown[]) {
  return (JSON.parse(String((call[1] as RequestInit).body)) as { events: unknown[] }).events
}

describe('track', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    flushTracking()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('batches events and sends them shortly after', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response('{}'))
    vi.stubGlobal('fetch', fetchMock)

    track('mic_started')
    track('mic_failed', { reason: 'NotAllowedError' })
    expect(fetchMock).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(2000)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/events')
    expect(sentEvents(fetchMock.mock.calls[0])).toEqual([
      { name: 'mic_started' },
      { name: 'mic_failed', props: { reason: 'NotAllowedError' } },
    ])
  })

  it('never lets a failing report surface as an error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline') }))

    track('quota_reached')
    await vi.advanceTimersByTimeAsync(2000)

    track('reconnect_failed')
    expect(() => flushTracking()).not.toThrow()
  })

  it('sends a full batch right away instead of letting the queue grow', () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response('{}'))
    vi.stubGlobal('fetch', fetchMock)

    for (let index = 0; index < 20; index += 1) track('page_view')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(sentEvents(fetchMock.mock.calls[0])).toHaveLength(20)
  })
})
