import { describe, expect, it } from 'vitest'
import { createRealtimeClient, type RealtimeEvent } from './realtime'

class FakeWebSocket {
  static OPEN = 1
  readyState = FakeWebSocket.OPEN
  sent: unknown[] = []
  listeners = new Map<string, Array<(event: MessageEvent | CloseEvent) => void>>()
  constructor(readonly url: string, readonly protocols: string[]) {}
  addEventListener(type: string, listener: (event: MessageEvent | CloseEvent) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }
  send(value: unknown) { this.sent.push(value) }
  close() {}
  emit(type: string, event: MessageEvent | CloseEvent) {
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }
}

describe('companion web realtime client', () => {
  it('starts a web stream and cancels playback on interruption', () => {
    const events: RealtimeEvent[] = []
    const client = createRealtimeClient({
      WebSocketCtor: FakeWebSocket as unknown as typeof WebSocket,
      streamUrl: 'wss://runtime.example/stream',
      runtimeToken: 'runtime-1',
      onEvent: (event) => events.push(event),
    })
    const socket = client.socket as unknown as FakeWebSocket
    client.start()
    const start = JSON.parse(String(socket.sent[0])) as Record<string, unknown>
    expect(start).toMatchObject({
      type: 'web.session.start', protocol_version: 1,
      audio: { format: 'pcm_s16le', sample_rate: 16000, channels: 1 },
    })
    expect(start.request_id).toEqual(expect.any(String))
    socket.emit('message', new MessageEvent('message', { data: JSON.stringify({
      type: 'turn.interrupted', turn_id: 'turn-1', details: { played_ms: 120 },
    }) }))
    expect(events[0]).toMatchObject({ type: 'turn.interrupted', turn_id: 'turn-1' })
    expect(client.playbackState()).toEqual({ clear: true, playedMs: 120 })
  })
})
