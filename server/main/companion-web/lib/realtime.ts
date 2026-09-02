export interface RealtimeEvent {
  type: string
  conversation_id?: string
  turn_id?: string | null
  event_id?: string
  request_id?: string
  segment_id?: string
  sequence?: number
  occurred_at?: number
  details: Record<string, unknown>
}

export interface ConversationDecodeState {
  lastSequence: number
  pendingAudioEvent: RealtimeEvent | null
}

export function decodeRealtimeMessage(data: unknown, state: ConversationDecodeState): RealtimeEvent {
  if (typeof data === 'string') {
    const parsed = JSON.parse(data) as RealtimeEvent
    if (typeof parsed.sequence === 'number' && typeof parsed.occurred_at === 'number') {
      if (parsed.sequence <= state.lastSequence) throw new Error('实时事件序列无效')
      state.lastSequence = parsed.sequence
    }
    if ((parsed.type === 'tts.audio' || parsed.type === 'tts.audio.chunk')
      && parsed.details?.transport === 'binary' && parsed.details?.data == null) state.pendingAudioEvent = parsed
    return parsed
  }
  if (state.pendingAudioEvent) {
    const event = state.pendingAudioEvent
    state.pendingAudioEvent = null
    return { ...event, details: { ...event.details, data } }
  }
  return { type: 'tts.audio.binary', details: { data } }
}

interface RealtimeClientOptions {
  streamUrl: string
  runtimeToken: string
  onEvent: (event: RealtimeEvent) => void
  onError?: (error: Error) => void
  onClose?: () => void
  WebSocketCtor?: typeof WebSocket
}

export function createRealtimeClient({
  streamUrl,
  runtimeToken,
  onEvent,
  onError,
  onClose,
  WebSocketCtor = WebSocket,
}: RealtimeClientOptions) {
  const socket = new WebSocketCtor(streamUrl, [`bearer.${runtimeToken}`])
  socket.binaryType = 'arraybuffer'
  let playback = { clear: false, playedMs: 0 }
  const decodeState: ConversationDecodeState = { lastSequence: 0, pendingAudioEvent: null }
  const pendingPayloads: string[] = []

  socket.addEventListener('open', () => {
    while (pendingPayloads.length && socket.readyState === (WebSocketCtor.OPEN ?? 1)) {
      socket.send(pendingPayloads.shift()!)
    }
  })

  socket.addEventListener('message', (message) => {
    if (typeof message.data !== 'string') {
      onEvent(decodeRealtimeMessage(message.data, decodeState))
      return
    }
    try {
      const parsed = decodeRealtimeMessage(message.data, decodeState)
      if (parsed.type === 'turn.interrupted') {
        playback = { clear: true, playedMs: Number(parsed.details.played_ms ?? 0) }
      }
      onEvent(parsed)
    } catch (error) {
      onError?.(error instanceof Error ? error : new Error('实时事件格式错误'))
    }
  })
  socket.addEventListener('error', () => onError?.(new Error('实时连接失败')))
  socket.addEventListener('close', () => onClose?.())

  function send(payload: Record<string, unknown>) {
    const serialized = JSON.stringify(payload)
    if (socket.readyState === (WebSocketCtor.OPEN ?? WebSocket.OPEN)) socket.send(serialized)
    else if (socket.readyState === 0) pendingPayloads.push(serialized)
    else throw new Error('实时连接尚未就绪')
  }

  return {
    socket,
    start() {
      send({
        type: 'web.session.start',
        protocol_version: 1,
        request_id: crypto.randomUUID(),
        audio: { format: 'pcm_s16le', sample_rate: 16000, channels: 1 },
      })
    },
    sendText(requestId: string, text: string) {
      send({ type: 'turn.text', request_id: requestId, event_id: crypto.randomUUID(), text })
    },
    pushAudio(bytes: Uint8Array) {
      if (socket.readyState === (WebSocketCtor.OPEN ?? WebSocket.OPEN)) socket.send(bytes.slice().buffer)
      else throw new Error('实时连接尚未就绪')
    },
    commit(requestId: string, durationMs: number) {
      send({ type: 'input.audio.commit', request_id: requestId, event_id: crypto.randomUUID(), duration_ms: durationMs })
    },
    cancel(turnId: string, playedMs = 0) {
      send({ type: 'response.cancel', turn_id: turnId, event_id: crypto.randomUUID(), played_ms: playedMs })
    },
    heartbeat() { send({ type: 'web.session.ping', last_sequence: decodeState.lastSequence }) },
    stop() { send({ type: 'stream.stop' }) },
    playbackState() { return { ...playback } },
    close() { socket.close() },
  }
}
