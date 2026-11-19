'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { ImagePlus, Mic, MicOff, Phone, PhoneOff, Send, Volume2 } from 'lucide-react'
import { DAILY_TURN_LIMIT_REACHED, ManagerError, managerJson, type ConversationSession, type WebAgent, type WebVoice } from '../lib/manager'
import { parseBootstrapMessage } from '../lib/bootstrap'
import AccountPanel from './AccountPanel'
import LoginPanel from './LoginPanel'
import RolePicker from './RolePicker'
import { createPlayback, type Playback } from '../lib/playback'
import { createRealtimeClient, type RealtimeEvent } from '../lib/realtime'
import { track } from '../lib/track'

const consoleOrigin = process.env.NEXT_PUBLIC_CONSOLE_ORIGIN || 'http://127.0.0.1:8001'
/** 单个会话服务端最多 100 轮。快到上限时在两轮之间换一个新会话，避免用户那句话撞上限被拒绝。 */
const SESSION_TURN_BUDGET = 90
const MAX_RECONNECT_ATTEMPTS = 6
const RECONNECT_BASE_MS = 1000
const RECONNECT_MAX_MS = 15_000
const AUTO_CONNECT_DELAY_MS = 150
/** 角色下拉框里“添加角色”这一项的值，不是真的角色。 */
const ADD_ROLE_OPTION = '__add_role__'
/** 连续聊了这么久之后提醒用户休息。中间停了一段时间就重新计时。 */
const LONG_USE_REMINDER_MS = 2 * 60 * 60 * 1000
const LONG_USE_IDLE_RESET_MS = 15 * 60 * 1000
const LONG_USE_CHECK_MS = 60 * 1000

interface AudioClip { bytes: Uint8Array; mimeType: string }

function audioPayload(details: Record<string, unknown>): AudioClip | null {
  let bytes: Uint8Array
  if (typeof details.data === 'string') {
    const binary = atob(details.data)
    bytes = new Uint8Array(binary.length)
    for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index)
  } else if (details.data instanceof ArrayBuffer) bytes = new Uint8Array(details.data)
  else if (details.data instanceof Uint8Array) bytes = details.data
  else return null
  const mimeType = typeof details.mime_type === 'string' ? details.mime_type : 'audio/wav'
  return { bytes, mimeType }
}

/** 麦克风不可用时给用户看的原因。手机上最常见的是页面不是 HTTPS，或者没有授权。 */
function microphoneProblem(reason: unknown) {
  const name = reason instanceof Error ? reason.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') return '没有麦克风权限，请在浏览器设置里允许后重试'
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return '没有找到可用的麦克风'
  if (name === 'NotReadableError') return '麦克风被其他应用占用了，请关闭后重试'
  return '麦克风打开失败，可以先用文字输入'
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
  // 是否已经问过服务端“现在有没有登录”。问完之前不显示登录表单，避免已登录的人看到表单闪一下。
  const [sessionChecked, setSessionChecked] = useState(false)
  const [sessionKind, setSessionKind] = useState<'app' | 'console'>('app')
  const [agents, setAgents] = useState<WebAgent[]>([])
  const [agentsLoaded, setAgentsLoaded] = useState(false)
  const [addingRole, setAddingRole] = useState(false)
  const [voices, setVoices] = useState<WebVoice[]>([])
  const [agentId, setAgentId] = useState('')
  const [voiceId, setVoiceId] = useState('')
  const [messages, setMessages] = useState<Array<{ role: 'user' | 'assistant'; text: string; clips?: AudioClip[]; image?: string }>>([])
  const [draft, setDraft] = useState('')
  const [imageData, setImageData] = useState<string[]>([])
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
  const playbackRef = useRef<Playback | null>(null)
  const messagesRef = useRef<HTMLDivElement>(null)
  // 用户往上翻看之前的消息时，新消息来了不要把页面拽回底部。
  const stickToBottomRef = useRef(true)
  const exchangedCodeRef = useRef<string | null>(null)
  // 会话只是一次连接：令牌 15 分钟过期、网络会断、单个会话有轮数上限。下面这些状态用来在
  // 用户无感的情况下换一个新会话；聊过的内容保存在服务端，新会话会自动接上。
  const generationRef = useRef(0)              // 每建立一次会话加一，旧会话迟到的事件一律忽略
  const wantConnectedRef = useRef(false)       // 用户是否希望保持连接
  const reconnectTimerRef = useRef<number | null>(null)
  const reconnectAttemptsRef = useRef(0)
  const renewPendingRef = useRef(false)        // 需要换新会话，但要等当前这一轮说完
  const turnActiveRef = useRef(false)
  const turnsInSessionRef = useRef(0)
  const pendingTextRef = useRef<{ text: string; images: string[] } | null>(null)
  /** 这次进入页面（或换了角色）之后，还没有请角色先开口。连上后请求一次，用户自己先说了话就不再请求。 */
  const openingPendingRef = useRef(false)
  /** 已经为哪一轮新建了回复消息。 */
  const replyTurnRef = useRef<string | null>(null)
  const startSessionRef = useRef<() => Promise<void>>(async () => undefined)
  const [reconnectFailed, setReconnectFailed] = useState(false)
  // 用户自己点了“断开”。这时不自动重连，也不接收输入，直到再点“连接”。
  const [paused, setPaused] = useState(false)
  // 今天的对话次数用完了。不再连接也不再重试，等用户明天再来。
  const [quotaExhausted, setQuotaExhausted] = useState(false)
  // 连续使用提醒：从什么时候开始连续在聊、最近一次说话是什么时候。
  const activeSinceRef = useRef<number | null>(null)
  const lastActivityRef = useRef(0)
  const [restReminder, setRestReminder] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)

  const fail = useCallback((reason: unknown) => {
    // 运行时的错误事件给的是字符串，不是 Error 对象，也要原样显示出来。
    if (reason instanceof Error) setError(reason.message)
    else if (typeof reason === 'string' && reason.trim()) setError(reason.trim())
    else setError('请求失败')
  }, [])

  const exchange = useCallback(async (code: string) => {
    const response = await fetch('/api/auth/exchange', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code }),
    })
    if (!response.ok) throw new Error('登录凭据已失效')
    setSessionKind('console')
    setAuthenticated(true)
    setSessionChecked(true)
    setStatus('已登录')
  }, [])

  const loadAgents = useCallback(async () => {
    const items = await managerJson<WebAgent[]>('/agents')
    const available = items.filter((item) => item.activeVersionNo != null && item.activeVersionNo > 0)
    setAgents(available)
    setAgentId((current) => available.some((item) => item.id === current) ? current : available[0]?.id || '')
    setAgentsLoaded(true)
  }, [])

  const signedIn = useCallback(() => {
    track('signed_in')
    setError('')
    setSessionKind('app')
    setAuthenticated(true)
    setStatus('已登录')
  }, [])

  useEffect(() => {
    let active = true
    if (window.parent !== window) window.parent.postMessage({ type: 'companion.ready' }, consoleOrigin)
    const queryCode = new URLSearchParams(window.location.search).get('code')
    if (queryCode) {
      // 登录码只能用一次。开发模式下这个副作用会执行两遍，第二遍不能再拿同一个码去换。
      if (exchangedCodeRef.current !== queryCode) {
        exchangedCodeRef.current = queryCode
        void exchange(queryCode).then(() => window.history.replaceState({}, '', '/'))
          .catch((reason) => { fail(reason); setSessionChecked(true) })
      }
    } else void fetch('/api/session', { cache: 'no-store' }).then(async (response) => {
      if (!active) return
      if (response.ok) {
        const payload = await response.json().catch(() => ({})) as { data?: { sessionKind?: string } }
        setSessionKind(payload.data?.sessionKind === 'console' ? 'console' : 'app')
        setAuthenticated(true); setStatus('已登录')
      }
      setSessionChecked(true)
    }).catch(() => { if (active) setSessionChecked(true) })
    const onMessage = (event: MessageEvent) => {
      const message = parseBootstrapMessage(event.data, event.origin, consoleOrigin)
      if (message) void exchange(message.code).catch(fail)
    }
    window.addEventListener('message', onMessage)
    return () => { active = false; window.removeEventListener('message', onMessage) }
  }, [exchange, fail])

  useEffect(() => {
    if (!authenticated) return
    void loadAgents().catch(fail)
  }, [authenticated, fail, loadAgents])

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

  const playback = useCallback(() => {
    if (!playbackRef.current) playbackRef.current = createPlayback()
    return playbackRef.current
  }, [])

  const stopPlayback = useCallback(() => { playbackRef.current?.stop() }, [])

  /** 重听一条回复：停掉正在播的，把这条回复的每一句按顺序再播一遍。 */
  const replay = useCallback((clips: AudioClip[]) => {
    const player = playback()
    player.stop()
    for (const clip of clips) player.play(clip.bytes, clip.mimeType)
    track('reply_replayed')
  }, [playback])

  // 手机浏览器只允许在用户手势里开始播放声音。第一次触摸或按键时解锁，之后到达的语音回复才能自动播放。
  useEffect(() => {
    const unlock = () => playback().unlock()
    window.addEventListener('pointerdown', unlock, { capture: true })
    window.addEventListener('keydown', unlock, { capture: true })
    return () => {
      window.removeEventListener('pointerdown', unlock, { capture: true })
      window.removeEventListener('keydown', unlock, { capture: true })
    }
  }, [playback])

  /** 用户说了一句话（打字或语音）。用来判断是不是在连续使用。 */
  const noteActivity = useCallback(() => {
    // 用户已经先开口了，角色就不用再打招呼。
    openingPendingRef.current = false
    const now = Date.now()
    if (activeSinceRef.current === null || now - lastActivityRef.current > LONG_USE_IDLE_RESET_MS) activeSinceRef.current = now
    lastActivityRef.current = now
  }, [])

  useEffect(() => {
    const timer = window.setInterval(() => {
      const since = activeSinceRef.current
      if (since === null) return
      const now = Date.now()
      if (now - lastActivityRef.current > LONG_USE_IDLE_RESET_MS) { activeSinceRef.current = null; return }
      if (now - since >= LONG_USE_REMINDER_MS) setRestReminder((shown) => { if (!shown) track('rest_reminder_shown'); return true })
    }, LONG_USE_CHECK_MS)
    return () => window.clearInterval(timer)
  }, [])

  /** 当前这一轮结束后调用：如果之前决定要换新会话，现在换。 */
  const settleTurn = useCallback((completed: boolean) => {
    turnActiveRef.current = false
    assistantTurnRef.current = null
    if (completed) turnsInSessionRef.current += 1
    if (renewPendingRef.current || turnsInSessionRef.current >= SESSION_TURN_BUDGET) void startSessionRef.current()
  }, [])

  const handleEvent = useCallback((event: RealtimeEvent) => {
    const details = event.details || {}
    if (event.type === 'session.ready') return
    if (event.type === 'stream.ready') {
      setConnected(true); setStatus('已连接'); setReconnectFailed(false)
      reconnectAttemptsRef.current = 0
      // 重连期间用户发的话先存着，连上之后补发。
      const pending = pendingTextRef.current
      if (pending && clientRef.current) {
        pendingTextRef.current = null
        try { clientRef.current.sendText(crypto.randomUUID(), pending.text, pending.images); setStatus('处理中') } catch { pendingTextRef.current = pending }
      } else if (openingPendingRef.current && clientRef.current) {
        // 隔了一段时间再回来时，角色会先说一句话。重连和换会话不会再请求。
        openingPendingRef.current = false
        try { clientRef.current.requestOpening(crypto.randomUUID()) } catch { /* 没请求成也不影响对话 */ }
      }
      return
    }
    if (event.type === 'asr.partial') { setPartial(String(details.text || '')); return }
    if (event.type === 'asr.final') {
      const text = String(details.text || '')
      setPartial('')
      if (text) { noteActivity(); setMessages((current) => [...current, { role: 'user', text }]) }
      return
    }
    if (event.type === 'reply.withdrawn') {
      // 服务端收回了说到一半的回复：把已经显示的那段换成它给的话，语音也不再保留。
      const text = String(details.text || '')
      track('reply_withdrawn')
      stopPlayback()
      setMessages((current) => {
        const last = current[current.length - 1]
        // 没有替换的话（被收回的是角色主动说的开场白）就直接拿掉。
        if (!text) return last?.role === 'assistant' ? current.slice(0, -1) : current
        if (last?.role === 'assistant') return [...current.slice(0, -1), { role: 'assistant', text }]
        return [...current, { role: 'assistant', text }]
      })
      return
    }
    if (event.type === 'turn.started') { assistantTurnRef.current = event.turn_id || null; turnActiveRef.current = true; return }
    if (event.type === 'llm.delta') {
      const text = String(details.text || '')
      // 每一轮的回复是一条新消息。角色先开口时，上一条也是角色说的，不能接在它后面。
      const fresh = Boolean(event.turn_id) && replyTurnRef.current !== event.turn_id
      if (event.turn_id) replyTurnRef.current = event.turn_id
      setMessages((current) => {
        const last = current[current.length - 1]
        if (!fresh && last?.role === 'assistant') return [...current.slice(0, -1), { ...last, text: last.text + text }]
        return [...current, { role: 'assistant', text }]
      })
      return
    }
    if (event.type === 'tts.audio' || event.type === 'tts.audio.chunk') {
      const payload = audioPayload(details)
      if (!payload) return
      // 语音是一句一句到的：到一句排队播一句，同时记在这条回复上，供用户重听。
      setMessages((current) => {
        const last = current[current.length - 1]
        if (last?.role === 'assistant') return [...current.slice(0, -1), { ...last, clips: [...(last.clips || []), payload] }]
        return [...current, { role: 'assistant', text: '', clips: [payload] }]
      })
      playback().play(payload.bytes, payload.mimeType)
      return
    }
    if (event.type === 'tts.audio.binary') return
    if (event.type === 'turn.interrupted' || event.type === 'output.audio.cleared') { stopPlayback(); setStatus('已连接'); return }
    if (event.type === 'session.expiring') {
      // 令牌快过期了：没有在说话就立刻换新会话，正在说话就等这一轮结束。
      if (turnActiveRef.current) renewPendingRef.current = true
      else void startSessionRef.current()
      return
    }
    if (event.type === 'session.expired') { setConnected(false); void startSessionRef.current(); return }
    if (event.type === 'error' || event.type.endsWith('.failed')) {
      if (details.code === 'turn_quota_exceeded') { void startSessionRef.current(); return }
      if (details.code === 'daily_quota_exceeded') { stopForQuotaRef.current(); return }
      // 失败后这一轮已经结束，状态要回到可以继续对话，不能一直停在“处理中”。
      track('turn_failed', { code: typeof details.code === 'string' ? details.code : 'unknown' })
      fail(details.message || details.code || '本轮失败'); setStatus('已连接'); settleTurn(false); return
    }
    if (event.type === 'turn.completed' || event.type === 'turn.cancelled' || event.type === 'turn.failed') {
      setStatus('已连接'); settleTurn(event.type === 'turn.completed')
    }
  }, [fail, noteActivity, playback, settleTurn, stopPlayback])

  /** 今天的次数用完了：停下来，不再自动连接。说到一半的话不会丢，但要等明天。 */
  const stopForQuota = useCallback(() => {
    wantConnectedRef.current = false
    generationRef.current += 1
    if (reconnectTimerRef.current !== null) { window.clearTimeout(reconnectTimerRef.current); reconnectTimerRef.current = null }
    pendingTextRef.current = null
    clientRef.current?.close(); clientRef.current = null
    setConnected(false); setLive(false); setQuotaExhausted(true); setError(''); setStatus('今日次数已用完')
    track('quota_reached')
  }, [])
  const stopForQuotaRef = useRef(stopForQuota)
  stopForQuotaRef.current = stopForQuota

  /** 连接意外中断或建立失败后，隔一会儿自动再试；连续失败多次才停下来让用户手动重连。 */
  const scheduleReconnect = useCallback((reason?: unknown) => {
    setConnected(false)
    if (!wantConnectedRef.current) { setStatus('未连接'); return }
    if (reconnectTimerRef.current !== null) return
    reconnectAttemptsRef.current += 1
    if (reconnectAttemptsRef.current > MAX_RECONNECT_ATTEMPTS) {
      setReconnectFailed(true); setStatus('连接已断开')
      track('reconnect_failed')
      if (reason) fail(reason)
      return
    }
    setStatus('正在重新连接')
    const delay = Math.min(RECONNECT_BASE_MS * 2 ** (reconnectAttemptsRef.current - 1), RECONNECT_MAX_MS)
    reconnectTimerRef.current = window.setTimeout(() => {
      reconnectTimerRef.current = null
      void startSessionRef.current()
    }, delay)
  }, [fail])

  const startSession = useCallback(async () => {
    if (!agentId) return
    const generation = ++generationRef.current
    if (reconnectTimerRef.current !== null) { window.clearTimeout(reconnectTimerRef.current); reconnectTimerRef.current = null }
    renewPendingRef.current = false; turnActiveRef.current = false; turnsInSessionRef.current = 0
    const current = () => generation === generationRef.current
    try {
      setError('')
      if (!clientRef.current) setStatus('正在连接')
      const session = await managerJson<ConversationSession>('/conversations', {
        method: 'POST', body: JSON.stringify({ agentId, inputModes: ['text', 'audio', 'image'], outputModes: ['text', 'audio'], ...(voiceId ? { voiceId } : {}) }),
      })
      if (!current()) return
      const previous = clientRef.current
      const client = createRealtimeClient({
        streamUrl: session.streamUrl,
        runtimeToken: session.runtimeToken,
        onEvent: (event) => { if (current()) handleEvent(event) },
        // 连接出错后紧跟着会收到关闭事件，由关闭事件统一安排重连，这里不单独报错。
        onError: () => undefined,
        onClose: () => { if (current()) scheduleReconnect(new Error('实时连接失败')) },
      })
      clientRef.current = client
      previous?.close()
      client.start()
    } catch (reason) {
      if (!current()) return
      if (reason instanceof ManagerError && reason.code === DAILY_TURN_LIMIT_REACHED) { stopForQuota(); return }
      scheduleReconnect(reason)
    }
  }, [agentId, handleEvent, scheduleReconnect, stopForQuota, voiceId])
  startSessionRef.current = startSession

  const connect = useCallback(() => {
    wantConnectedRef.current = true
    reconnectAttemptsRef.current = 0
    setReconnectFailed(false); setPaused(false)
    void startSessionRef.current()
  }, [])

  // 选好角色（和音色）后自动连接，不需要用户手动点。稍等一下再连，避免角色和音色先后变化时连两次。
  useEffect(() => {
    if (!authenticated || !agentId) return
    wantConnectedRef.current = true
    reconnectAttemptsRef.current = 0
    setPaused(false); setReconnectFailed(false); setQuotaExhausted(false)
    const timer = window.setTimeout(() => { void startSessionRef.current() }, AUTO_CONNECT_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [authenticated, agentId, voiceId])

  // 换角色或重新进入时，先显示和这个角色之前聊过的内容。
  useEffect(() => {
    if (!authenticated || !agentId) return
    let active = true
    setMessages([])
    openingPendingRef.current = true
    managerJson<Array<{ text?: unknown; reply?: unknown }>>(`/history?agentId=${encodeURIComponent(agentId)}`)
      .then((items) => {
        if (!active || !Array.isArray(items)) return
        // 用户那句话为空的是角色之前主动说的开场白，只显示角色这一边。
        const earlier = items.flatMap((item) => typeof item.text === 'string' && typeof item.reply === 'string'
          ? [...(item.text ? [{ role: 'user' as const, text: item.text }] : []), { role: 'assistant' as const, text: item.reply }]
          : [])
        // 历史加载期间用户可能已经开始说话，新消息排在历史后面。
        setMessages((current) => [...earlier, ...current])
      })
      .catch(() => undefined)
    return () => { active = false }
  }, [authenticated, agentId])

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
    if (!navigator.mediaDevices?.getUserMedia) {
      // 浏览器只在 HTTPS（或本机地址）下提供麦克风。用局域网地址在手机上打开时会走到这里。
      track('mic_failed', { reason: 'insecure_context' })
      fail('这个页面现在用不了麦克风：需要用 HTTPS 地址打开。可以先用文字输入')
      return
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } })
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
          if (!state.active && playbackRef.current?.isPlaying() && assistantTurnRef.current && clientRef.current) {
            clientRef.current.cancel(String(assistantTurnRef.current), playbackRef.current.playedMs())
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
      setLive(true); setStatus('正在听')
      track('mic_started')
    } catch (reason) {
      track('mic_failed', { reason: reason instanceof Error ? reason.name : 'unknown' })
      fail(microphoneProblem(reason))
    }
  }, [connected, fail, stopPlayback])

  const endLive = useCallback(async () => {
    const state = segmentRef.current
    if (state.active && clientRef.current) clientRef.current.commit(crypto.randomUUID(), Math.round(state.durationMs))
    await stopCapture(); clientRef.current?.stop(); setLive(false); setStatus('已连接')
  }, [stopCapture])

  const sendText = useCallback(() => {
    const text = draft.trim()
    if (!text && imageData.length === 0) return
    if (!wantConnectedRef.current) return
    noteActivity()
    setMessages((current) => [...current, { role: 'user', text: text || '图片', ...(imageData[0] ? { image: imageData[0] } : {}) }])
    setDraft(''); setImageData([])
    if (clientRef.current && connected) {
      try { clientRef.current.sendText(crypto.randomUUID(), text, imageData); setStatus('处理中'); return } catch { /* 连接刚断开，按重连处理 */ }
    }
    // 正在重连：先把这句话存起来，连上之后自动补发，用户不用重新输入。
    pendingTextRef.current = { text, images: imageData }
    if (reconnectTimerRef.current === null && !connected) void startSessionRef.current()
  }, [connected, draft, imageData, noteActivity])

  const disconnect = useCallback(async () => {
    wantConnectedRef.current = false
    generationRef.current += 1
    if (reconnectTimerRef.current !== null) { window.clearTimeout(reconnectTimerRef.current); reconnectTimerRef.current = null }
    pendingTextRef.current = null
    await stopCapture(); clientRef.current?.close(); clientRef.current = null
    setConnected(false); setLive(false); setStatus('未连接'); setPaused(true)
  }, [stopCapture])

  useEffect(() => () => {
    wantConnectedRef.current = false
    generationRef.current += 1
    if (reconnectTimerRef.current !== null) window.clearTimeout(reconnectTimerRef.current)
    void stopCapture(); clientRef.current?.close(); playbackRef.current?.dispose(); playbackRef.current = null
  }, [stopCapture])

  const signOut = useCallback(async () => {
    await disconnect(); stopPlayback()
    await fetch('/api/auth/logout', { method: 'POST' }).catch(() => undefined)
    setAuthenticated(false); setAgents([]); setAgentsLoaded(false); setAgentId(''); setMessages([])
    setError(''); setStatus('已退出'); setReconnectFailed(false); setPaused(false); setQuotaExhausted(false)
  }, [disconnect, stopPlayback])

  // 新消息到来时滚到底部，除非用户自己往上翻了。
  useEffect(() => {
    const element = messagesRef.current
    if (element && stickToBottomRef.current) element.scrollTop = element.scrollHeight
  }, [messages, partial])

  // 重连期间仍然可以输入，发出的话会在连上后补发；只有手动断开或重连彻底失败时才停用输入。
  const canType = Boolean(agentId) && !paused && !reconnectFailed && !quotaExhausted

  if (!authenticated) {
    if (!sessionChecked) return <main className="companion-shell"><div className="loading-state">{error || status}</div></main>
    return <LoginPanel onSignedIn={signedIn} />
  }
  // 手机号登录的新账号还没有角色：先选一个。从管理台过来的会话在管理台里管理角色。
  if (agentsLoaded && agents.length === 0 && sessionKind === 'app') {
    return <RolePicker onCreated={() => { track('role_created'); void loadAgents().catch(fail) }} onSignOut={() => void signOut()} />
  }
  // 已经有角色的用户再添加一个。添加完回到对话，新角色出现在角色列表里。
  if (addingRole && sessionKind === 'app') {
    return <RolePicker
      existingNames={agents.map((agent) => agent.agentName || agent.name || '')}
      onBack={() => setAddingRole(false)}
      onCreated={(id) => {
        track('role_added'); setAddingRole(false)
        // 添加完直接切到新角色。
        void loadAgents().then(() => { if (id) setAgentId(id) }).catch(fail)
      }}
      onSignOut={() => void signOut()}
    />
  }
  const roleName = selectedAgent?.agentName || selectedAgent?.name || '陪伴角色'
  const online = connected
  return <main className="chat-shell">
    <header className="chat-header">
      <div className="chat-title">
        <p className="kicker">AI PLUSH COMPANION</p>
        <h1>{roleName}</h1>
      </div>
      <div className="header-actions">
        <span className={`connection-pill ${online ? 'online' : ''}`} role="status">{status}</span>
        {sessionKind === 'app' && <button type="button" className="link-button" onClick={() => setAccountOpen(true)}>账号与数据</button>}
      </div>
    </header>
    <section className="setup-bar">
      <label>角色<select value={agentId} onChange={(event) => { if (event.target.value === ADD_ROLE_OPTION) setAddingRole(true); else setAgentId(event.target.value) }}>{agents.length === 0 && <option value="">暂无已发布角色</option>}{agents.map((agent) => <option key={agent.id} value={agent.id}>{agent.agentName || agent.name || agent.id}</option>)}{sessionKind === 'app' && <option value={ADD_ROLE_OPTION}>＋ 添加角色</option>}</select></label>
      <label>音色<select value={voiceId} onChange={(event) => setVoiceId(event.target.value)}><option value="">使用角色默认音色</option>{voices.map((voice) => <option key={voice.id} value={voice.id}>{voice.name}</option>)}</select></label>
      {reconnectFailed || paused
        ? <button className="button primary" onClick={connect} disabled={!agentId || quotaExhausted} aria-label={paused ? '连接' : '重新连接'}><Phone size={16} /><span className="button-label">{paused ? '连接' : '重新连接'}</span></button>
        : <button className="button" onClick={() => void disconnect()} disabled={!connected} aria-label="断开"><PhoneOff size={16} /><span className="button-label">断开</span></button>}
    </section>
    {error && <div className="error-banner" role="alert">{error}</div>}
    {restReminder && <div className="notice-banner quota-banner rest-banner" role="status">
      <span>已经连续聊了两个小时啦，起来活动一下、喝点水，休息一会儿再聊吧。</span>
      <button type="button" className="link-button" onClick={() => { setRestReminder(false); activeSinceRef.current = Date.now() }}>知道了</button>
    </div>}
    {quotaExhausted && <div className="notice-banner quota-banner" role="status">今天聊得够多啦，明天再来找{roleName}吧。之前聊过的内容都还在。</div>}
    <section className="conversation-panel">
      <div
        className="messages" aria-live="polite" ref={messagesRef}
        onScroll={(event) => {
          const element = event.currentTarget
          stickToBottomRef.current = element.scrollHeight - element.scrollTop - element.clientHeight < 96
        }}
      >
        {messages.length === 0 && <div className="empty-conversation"><Volume2 size={26} /><p>和{roleName}说点什么吧</p><span>可以打字，也可以点麦克风直接说</span></div>}
        {messages.map((message, index) => <article className={`message ${message.role}`} key={`${index}-${message.text}`}><div className="message-role">{message.role === 'user' ? '你' : 'AI'}</div><div className="message-body">{message.text}{message.image && <img className="message-image" src={message.image} alt="已发送图片" />}{message.clips?.length ? <button type="button" className="replay" onClick={() => replay(message.clips!)}>重听</button> : null}</div></article>)}
        {partial && <article className="message user partial"><div className="message-role">识别中</div><div className="message-body">{partial}</div></article>}
      </div>
      {(live || speaking) && <div className="listening-bar" role="status">{speaking ? '正在听你说…' : '麦克风已打开，直接说话就可以'}</div>}
      <div className="composer">
        <label className="icon-button secondary" aria-label="添加图片"><ImagePlus size={18} /><input type="file" accept="image/*" hidden disabled={!connected} onChange={(event) => { const file = event.target.files?.[0]; if (!file || file.size > 6 * 1024 * 1024) return; const reader = new FileReader(); reader.onload = () => setImageData([String(reader.result)]); reader.readAsDataURL(file); event.target.value = '' }} /></label>
        <textarea
          aria-label="输入消息" rows={1} enterKeyHint="send" value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); sendText() } }}
          placeholder={imageData.length ? '补充图片说明...' : '输入消息...'} disabled={!canType}
        />
        <button
          type="button" className={`icon-button mic ${live ? 'active' : ''}`} disabled={!connected}
          aria-label={live ? '结束实时通话' : '开始实时通话'} aria-pressed={live}
          onClick={() => void (live ? endLive() : startCapture())}
        >{live ? <MicOff size={18} /> : <Mic size={18} />}</button>
        <button type="button" className="icon-button" onClick={sendText} disabled={!canType || (!draft.trim() && imageData.length === 0)} aria-label="发送"><Send size={18} /></button>
      </div>
    </section>
    <p className="ai-disclosure">{roleName}是 AI，不是真人。回复由 AI 生成，可能不准确。</p>
    {accountOpen && <AccountPanel
      roleName={roleName}
      agentId={agentId}
      onClose={() => setAccountOpen(false)}
      onHistoryCleared={() => {
        // 服务端的记录已经清空。换一个新会话，让角色从空白开始，页面上的消息也清掉。
        track('history_cleared')
        setMessages([])
        void startSessionRef.current()
      }}
      onSignOut={() => { setAccountOpen(false); void signOut() }}
      onAccountDeleted={() => { setAccountOpen(false); void signOut() }}
    />}
  </main>
}
