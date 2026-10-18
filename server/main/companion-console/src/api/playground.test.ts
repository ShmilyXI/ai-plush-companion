import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as httpApi from './http'
import { connectPlaygroundConversation, createPlaygroundConversation } from './playground'

vi.mock('./http', async () => {
  const actual = await vi.importActual<typeof import('./http')>('./http')
  return { ...actual, default: { post: vi.fn() } }
})

class FakeWebSocket {
  static OPEN = 1
  readyState = FakeWebSocket.OPEN
  binaryType = ''
  sent: unknown[] = []
  listeners = new Map<string, Array<(event: MessageEvent | Event | CloseEvent) => void>>()

  constructor(readonly url: string, readonly protocols: string[]) {}

  addEventListener(type: string, listener: (event: MessageEvent | Event | CloseEvent) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  send(value: unknown) { this.sent.push(value) }
  close() {}
}

const session = {
  conversationId: 'conversation-a', agentId: 'agent-a', agentVersion: 7,
  streamUrl: 'ws://runtime.test/api/v1/conversations/conversation-a/stream', runtimeToken: 'runtime-token',
  expiresAt: '2026-08-26T12:00:00Z', inputModes: ['text', 'audio'], outputModes: ['text', 'audio'],
  publicMetadata: { agent_name: '小夏', tts_voice_id: 'voice-a' },
}

describe('playground public conversation api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('WebSocket', FakeWebSocket)
  })

  it('creates a session through the public conversation endpoint', async () => {
    vi.mocked(httpApi.default.post).mockResolvedValue({ data: { code: 0, msg: 'ok', data: session } } as never)

    await expect(createPlaygroundConversation({
      agentId: 'agent-a', voiceId: 'voice-a', modelOverrides: { LLM: 'llm-a' },
    })).resolves.toMatchObject({ conversationId: 'conversation-a', agentVersion: 7 })

    expect(httpApi.default.post).toHaveBeenCalledWith('/api/v1/conversations', {
      agentId: 'agent-a', inputModes: ['text', 'audio'], outputModes: ['text', 'audio'],
      voiceId: 'voice-a', modelOverrides: { LLM: 'llm-a' },
    }, undefined)
  })

  it('connects with the runtime token and sends the v1 text frame', () => {
    const client = connectPlaygroundConversation(session, { onEvent: vi.fn() })
    const socket = client.socket as unknown as FakeWebSocket

    expect(socket.url).toBe(session.streamUrl)
    expect(socket.protocols).toEqual(['bearer.runtime-token'])

    client.sendText('request-a', '你好')
    expect(socket.sent).toEqual([JSON.stringify({ type: 'turn.text', request_id: 'request-a', text: '你好' })])
  })

  it('sends audio as start, pcm bytes, and end frames', () => {
    const client = connectPlaygroundConversation(session, { onEvent: vi.fn() })
    const socket = client.socket as unknown as FakeWebSocket
    const pcm = new Uint8Array([1, 2, 3, 4])

    client.sendAudio('request-a', pcm, 120)

    expect(socket.sent[0]).toBe(JSON.stringify({ type: 'turn.audio.start', request_id: 'request-a', duration_ms: 120 }))
    expect(socket.sent[1]).toBeInstanceOf(ArrayBuffer)
    expect([...new Uint8Array(socket.sent[1] as ArrayBuffer)]).toEqual([1, 2, 3, 4])
    expect(socket.sent[2]).toBe(JSON.stringify({ type: 'turn.audio.end' }))
  })
})
