import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CompanionApp from './CompanionApp'

class FakeWebSocket {
  static OPEN = 1
  static CONNECTING = 0
  readyState = FakeWebSocket.OPEN
  binaryType = ''
  listeners = new Map<string, Array<(event: MessageEvent | Event) => void>>()
  constructor(readonly url: string, readonly protocols: string[]) {}
  addEventListener(type: string, listener: (event: MessageEvent | Event) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }
  send(value: unknown) {
    if (typeof value !== 'string') return
    const payload = JSON.parse(value) as { type?: string; request_id?: string }
    if (payload.type === 'web.session.start') this.emit({ type: 'stream.ready', details: { format: 'pcm_s16le' } })
    if (payload.type === 'turn.text') {
      this.emit({ type: 'turn.started', turn_id: 'turn-1', details: { request_id: payload.request_id, input_mode: 'text' } })
      this.emit({ type: 'llm.delta', turn_id: 'turn-1', details: { text: '你好，' } })
      this.emit({ type: 'llm.delta', turn_id: 'turn-1', details: { text: '我在线。' } })
      this.emit({ type: 'turn.completed', turn_id: 'turn-1', details: { text: '你好，我在线。' } })
    }
  }
  close() { this.emitClose() }
  emit(value: Record<string, unknown>) {
    const data = JSON.stringify({ conversation_id: 'conversation-a', sequence: 1, ...value })
    for (const listener of this.listeners.get('message') ?? []) listener(new MessageEvent('message', { data }))
  }
  emitClose() { for (const listener of this.listeners.get('close') ?? []) listener(new CloseEvent('close')) }
}

describe('CompanionApp', () => {
  beforeEach(() => {
    vi.stubGlobal('WebSocket', FakeWebSocket)
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/session') return new Response(JSON.stringify({ data: { id: '7', username: 'demo' } }), { status: 200 })
      if (url === '/api/agents') return new Response(JSON.stringify({ data: [{
        id: 'agent-a', agentName: '小夏', activeVersionNo: 4, ttsModelId: 'tts-a', ttsVoiceId: 'voice-a',
      }] }), { status: 200 })
      if (url.startsWith('/api/voices')) return new Response(JSON.stringify({ data: [{ id: 'voice-a', name: '女声', ttsModelId: 'tts-a' }] }), { status: 200 })
      if (url === '/api/conversations') return new Response(JSON.stringify({ data: {
        conversationId: 'conversation-a', agentId: 'agent-a', agentVersion: 4,
        streamUrl: 'ws://runtime.example/stream', runtimeToken: 'runtime-token', expiresAt: '2099-01-01',
        inputModes: ['text', 'audio'], outputModes: ['text', 'audio'],
      } }), { status: 200 })
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 })
    }))
  })

  it('loads configured role and voice, then sends a text turn on the realtime connection', async () => {
    render(<CompanionApp />)
    expect(await screen.findByRole('option', { name: '小夏' })).toBeVisible()
    expect(await screen.findByRole('option', { name: '女声' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '连接' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    fireEvent.change(screen.getByRole('textbox', { name: '输入消息' }), { target: { value: '你好' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await screen.findByText('你好，我在线。')).toBeVisible()
  })
})
