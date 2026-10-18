'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { Mic, MicOff, Phone, PhoneOff, Send, Volume2 } from 'lucide-react'
import { managerJson, type ConversationSession, type WebAgent, type WebVoice } from '../lib/manager'
import { parseBootstrapMessage } from '../lib/bootstrap'
import { createRealtimeClient, type RealtimeEvent } from '../lib/realtime'

const consoleOrigin = process.env.NEXT_PUBLIC_CONSOLE_ORIGIN || 'http://127.0.0.1:8001'

function decodeAudio(details: Record<string, unknown>) {
  let bytes: Uint8Array
  if (typeof details.data === 'string') {
    const binary = atob(details.data)
    bytes = new Uint8Array(binary.length)
    for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index)
  } else if (details.data instanceof ArrayBuffer) bytes = new Uint8Array(details.data)
  else if (details.data instanceof Uint8Array) bytes = details.data
  else return null
  const mime = typeof details.mime_type === 'string' ? details.mime_type : 'audio/wav'
  return URL.createObjectURL(new Blob([bytes.buffer as ArrayBuffer], { type: mime }))
}

function pcm16(input: Float32Array) {
  const output = new Uint8Array(input.length * 2)
  const view = new DataView(output.buffer)
  input.forEach((value, index) => view.setInt16(index * 2, Math.max(-1, Math.min(1, value)) * 0x7fff, true))
  return output
}

function downsample(input: Float32Array, inputRate: number, outputRate = 16_000) {
  if (inputRate === outputRate) return input
  const ratio = inputRate / outputRate
  const output = new Float32Array(Math.max(1, Math.round(input.length / ratio)))
  for (let index = 0; index < output.length; index += 1) {
    const start = Math.floor(index * ratio)
    const end = Math.min(input.length, Math.floor((index + 1) * ratio))
    let sum = 0
    for (let source = start; source < end; source += 1) sum += input[source]
    output[index] = sum / Math.max(1, end - start)
  }
  return output
}

export default function CompanionApp() {
  const [authenticated, setAuthenticated] = useState(false)
  const [agents, setAgents] = useState<WebAgent[]>([])
  const [voices, setVoices] = useState<WebVoice[]>([])
  const [agentId, setAgentId] = useState('')
  const [voiceId, setVoiceId] = useState('')
  const [messages, setMessages] = useState<Array<{ role: 'user' | 'assistant'; text: string; audio?: string }>>([])
  const [draft, setDraft] = useState('')
  const [status, setStatus] = useState('正在验证登录')
  const [connected, setConnected] = useState(false)
  const [live, setLive] = useState(false)
  const [speaking, setSpeaking] = useState(false)
  const [partial, setPartial] = useState('')
  const [error, setError] = useState('')
  const clientRef = useRef<ReturnType<typeof createRealtimeClient> | null>(null)
  const audioContextRef = useRef<AudioContext | null>(null)
  const mediaStreamRef = useRef<MediaStream | null>(null)
  const processorRef = useRef<ScriptProcessorNode | null>(null)
  const sourceRef = useRef<MediaStreamAudioSourceNode | null>(null)
  const segmentRef = useRef({ active: false, silenceMs: 0, durationMs: 0 })
  const assistantTurnRef = useRef<string | null>(null)
  const playbackRef = useRef<HTMLAudioElement[]>([])
  const playbackQueueRef = useRef<HTMLAudioElement[]>([])
  const playbackActiveRef = useRef(false)

  const fail = useCallback((reason: unknown) => {
    setError(reason instanceof Error ? reason.message : '请求失败')
  }, [])

  const exchange = useCallback(async (code: string) => {
    const response = await fetch('/api/auth/exchange', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code }),
    })
    if (!response.ok) throw new Error('登录凭据已失效')
    setAuthenticated(true)
    setStatus('已登录')
  }, [])

  useEffect(() => {
    let active = true
    if (window.parent !== window) window.parent.postMessage({ type: 'companion.ready' }, consoleOrigin)
    const queryCode = new URLSearchParams(window.location.search).get('code')
    if (queryCode) void exchange(queryCode).then(() => window.history.replaceState({}, '', '/')).catch(fail)
    else void fetch('/api/session', { cache: 'no-store' }).then(async (response) => {
      if (!active) return
      if (response.ok) { setAuthenticated(true); setStatus('已登录'); return }
      setStatus('网页对话暂未开放')
    }).catch(fail)
    const onMessage = (event: MessageEvent) => {
      const message = parseBootstrapMessage(event.data, event.origin, consoleOrigin)
      if (message) void exchange(message.code).catch(fail)
    }
    window.addEventListener('message', onMessage)
    return () => { active = false; window.removeEventListener('message', onMessage) }
  }, [exchange, fail])

  useEffect(() => {
    if (!authenticated) return
    void managerJson<WebAgent[]>('/agents').then((items) => {
      const available = items.filter((item) => item.activeVersionNo == null || item.activeVersionNo > 0)
      setAgents(available)
      if (available[0]) setAgentId(available[0].id)
    }).catch(fail)
  }, [authenticated, fail])

  useEffect(() => {
    if (!connected || !clientRef.current) return
    const timer = window.setInterval(() => {
      try { clientRef.current?.heartbeat() } catch { /* the close handler updates the UI */ }
    }, 20_000)
    return () => window.clearInterval(timer)
  }, [connected])

  const selectedAgent = agents.find((item) => item.id === agentId)
  useEffect(() => {
    setVoiceId(selectedAgent?.ttsVoiceId || '')
    if (!selectedAgent?.ttsModelId) { setVoices([]); return }
    void managerJson<WebVoice[]>(`/voices?ttsModelId=${encodeURIComponent(selectedAgent.ttsModelId)}`)
      .then(setVoices).catch(fail)
  }, [selectedAgent, fail])

  const stopPlayback = useCallback(() => {
    playbackRef.current.forEach((audio) => { audio.pause(); URL.revokeObjectURL(audio.src) })
    playbackRef.current = []; playbackQueueRef.current = []; playbackActiveRef.current = false
  }, [])

  const playAudio = useCallback((details: Record<string, unknown>) => {
    const url = decodeAudio(details)
    if (!url) return
    const audio = new Audio(url)
    playbackRef.current.push(audio)
    playbackQueueRef.current.push(audio)
    const playNext = () => {
      if (playbackActiveRef.current) return
      const next = playbackQueueRef.current.shift()
      if (!next) return
      playbackActiveRef.current = true
      next.onended = () => {
        playbackRef.current = playbackRef.current.filter((item) => item !== next)
        URL.revokeObjectURL(next.src); playbackActiveRef.current = false; playNext()
      }
      void next.play().catch(() => { playbackActiveRef.current = false; playNext() })
    }
    playNext()
  }, [])

  const handleEvent = useCallback((event: RealtimeEvent) => {
    const details = event.details || {}
    if (event.type === 'session.ready') { setStatus('正在协商实时音频'); return }
    if (event.type === 'stream.ready') { setConnected(true); setStatus('已连接'); return }
    if (event.type === 'asr.partial') { setPartial(String(details.text || '')); return }
    if (event.type === 'asr.final') {
      const text = String(details.text || '')
      setPartial('')
      if (text) setMessages((current) => [...current, { role: 'user', text }])
      return
    }
    if (event.type === 'turn.started') { assistantTurnRef.current = event.turn_id || null; return }
    if (event.type === 'llm.delta') {
      const text = String(details.text || '')
      setMessages((current) => {
        const last = current[current.length - 1]
        if (last?.role === 'assistant') return [...current.slice(0, -1), { ...last, text: last.text + text }]
        return [...current, { role: 'assistant', text }]
      })
      return
    }
    if (event.type === 'tts.audio' || event.type === 'tts.audio.chunk') {
      const url = decodeAudio(details)
      if (url) setMessages((current) => {
        const last = current[current.length - 1]
        if (last?.role === 'assistant') return [...current.slice(0, -1), { ...last, audio: url }]
        return [...current, { role: 'assistant', text: '', audio: url }]
      })
      playAudio(details)
      return
    }
    if (event.type === 'tts.audio.binary') return
    if (event.type === 'turn.interrupted' || event.type === 'output.audio.cleared') { stopPlayback(); setStatus('已连接'); return }
    if (event.type === 'session.expiring') { setStatus('会话即将过期'); return }
    if (event.type === 'session.expired') { setConnected(false); setLive(false); setStatus('会话已过期'); return }
    if (event.type === 'error' || event.type.endsWith('.failed')) { fail(details.message || details.code || '本轮失败'); return }
    if (event.type === 'turn.completed' || event.type === 'turn.cancelled' || event.type === 'turn.failed') { assistantTurnRef.current = null; setStatus('已连接') }
  }, [fail, playAudio, stopPlayback])

  const startSession = useCallback(async () => {
    if (!agentId) return
    try {
      setError(''); setStatus('正在连接')
      const session = await managerJson<ConversationSession>('/conversations', {
        method: 'POST', body: JSON.stringify({ agentId, inputModes: ['text', 'audio'], outputModes: ['text', 'audio'], ...(voiceId ? { voiceId } : {}) }),
      })
      clientRef.current?.close()
      const client = createRealtimeClient({ streamUrl: session.streamUrl, runtimeToken: session.runtimeToken, onEvent: handleEvent, onError: fail, onClose: () => { setConnected(false); setLive(false); setStatus('连接已关闭') } })
      clientRef.current = client
      setConnected(false)
      setStatus('等待实时连接')
      client.start()
    } catch (reason) { fail(reason); setStatus('连接失败') }
  }, [agentId, fail, handleEvent, voiceId])

  const stopCapture = useCallback(async () => {
    processorRef.current?.disconnect(); sourceRef.current?.disconnect()
    mediaStreamRef.current?.getTracks().forEach((track) => track.stop())
    if (audioContextRef.current) await audioContextRef.current.close().catch(() => undefined)
    processorRef.current = null; sourceRef.current = null; mediaStreamRef.current = null; audioContextRef.current = null
    segmentRef.current = { active: false, silenceMs: 0, durationMs: 0 }
    setSpeaking(false)
  }, [])

  const startCapture = useCallback(async () => {
    if (!clientRef.current || !connected || audioContextRef.current) return
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true } })
      const context = new AudioContext()
      const source = context.createMediaStreamSource(stream)
      const processor = context.createScriptProcessor(2048, 1, 1)
      const sink = context.createGain(); sink.gain.value = 0
      processor.addEventListener('audioprocess', (event) => {
        const frame = downsample(event.inputBuffer.getChannelData(0), context.sampleRate)
        const sum = frame.reduce((total, value) => total + value * value, 0)
        const rms = Math.sqrt(sum / Math.max(1, frame.length))
        const state = segmentRef.current
        const frameMs = frame.length / 16
        try { clientRef.current?.pushAudio(pcm16(frame)) } catch { return }
        if (rms > 0.04) {
          if (!state.active && playbackRef.current.length && assistantTurnRef.current && clientRef.current) {
            const playedMs = Math.round((playbackRef.current[0]?.currentTime || 0) * 1000)
            clientRef.current.cancel(String(assistantTurnRef.current), playedMs)
            stopPlayback()
          }
          state.active = true; state.silenceMs = 0; setSpeaking(true)
        }
        if (state.active) {
          state.durationMs += frameMs
          if (rms <= 0.015) state.silenceMs += frameMs
          else state.silenceMs = 0
          if (state.silenceMs >= 700) {
            const requestId = crypto.randomUUID()
            clientRef.current?.commit(requestId, Math.round(state.durationMs))
            state.active = false; state.silenceMs = 0; state.durationMs = 0; setSpeaking(false)
          }
        }
      })
      source.connect(processor); processor.connect(sink); sink.connect(context.destination)
      audioContextRef.current = context; sourceRef.current = source; processorRef.current = processor; mediaStreamRef.current = stream
      setLive(true); setStatus('持续收音中')
    } catch (reason) { fail(reason) }
  }, [connected, fail, stopPlayback])

  const endLive = useCallback(async () => {
    const state = segmentRef.current
    if (state.active && clientRef.current) clientRef.current.commit(crypto.randomUUID(), Math.round(state.durationMs))
    await stopCapture(); clientRef.current?.stop(); setLive(false); setStatus('已连接')
  }, [stopCapture])

  const sendText = useCallback(() => {
    const text = draft.trim()
    if (!text || !clientRef.current || !connected) return
    setMessages((current) => [...current, { role: 'user', text }])
    clientRef.current.sendText(crypto.randomUUID(), text); setDraft(''); setStatus('处理中')
  }, [connected, draft])

  useEffect(() => () => { void stopCapture(); clientRef.current?.close(); stopPlayback() }, [stopCapture, stopPlayback])

  if (!authenticated) return <main className="companion-shell"><div className="loading-state">{error || status}</div></main>
  return <main className="companion-shell">
    <header className="companion-header"><div><p className="kicker">AI PLUSH COMPANION</p><h1>陪伴对话</h1><p className="subtle">选择一个角色，开始文字或语音通话</p></div><span className={`connection-pill ${connected ? 'online' : ''}`}>{status}</span></header>
    <section className="setup-bar">
      <label>角色<select value={agentId} onChange={(event) => setAgentId(event.target.value)}>{agents.length === 0 && <option value="">暂无已发布角色</option>}{agents.map((agent) => <option key={agent.id} value={agent.id}>{agent.agentName || agent.name || agent.id}</option>)}</select></label>
      <label>音色<select value={voiceId} onChange={(event) => setVoiceId(event.target.value)}><option value="">使用角色默认音色</option>{voices.map((voice) => <option key={voice.id} value={voice.id}>{voice.name}</option>)}</select></label>
      <button className="button primary" onClick={() => void startSession()} disabled={!agentId || connected}><Phone size={16} />连接</button>
      <button className="button" onClick={() => { clientRef.current?.close(); setConnected(false); setLive(false); setStatus('未连接') }} disabled={!connected}><PhoneOff size={16} />断开</button>
    </section>
    {error && <div className="error-banner" role="alert">{error}</div>}
    <section className="conversation-panel">
      <div className="messages" aria-live="polite">
        {messages.length === 0 && <div className="empty-conversation"><Volume2 size={26} /><p>连接角色后开始对话</p><span>可以输入文字，也可以开启持续语音</span></div>}
        {messages.map((message, index) => <article className={`message ${message.role}`} key={`${index}-${message.text}`}><div className="message-role">{message.role === 'user' ? '你' : 'AI'}</div><div className="message-body">{message.text}{message.audio && <audio controls src={message.audio} />}</div></article>)}
        {partial && <article className="message user partial"><div className="message-role">识别中</div><div className="message-body">{partial}</div></article>}
      </div>
      <div className="composer"><textarea aria-label="输入消息" value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); sendText() } }} placeholder="输入消息..." disabled={!connected} /><button className="icon-button" onClick={sendText} disabled={!connected || !draft.trim()} aria-label="发送"><Send size={18} /></button></div>
    </section>
    <section className="call-controls"><button className={`call-button ${live ? 'active' : ''}`} onClick={() => void (live ? endLive() : startCapture())} disabled={!connected}>{live ? <MicOff size={22} /> : <Mic size={22} />}<span>{live ? '结束实时通话' : '开始实时通话'}</span></button>{speaking && <span className="speaking-indicator">正在听</span>}</section>
  </main>
}
