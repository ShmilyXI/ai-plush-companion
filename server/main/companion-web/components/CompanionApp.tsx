'use client'

import { Fragment, useCallback, useEffect, useRef, useState } from 'react'
import { Menu, Minimize2, RotateCw, Smile, Volume2, VolumeX, X } from 'lucide-react'
import { DAILY_TURN_LIMIT_REACHED, ManagerError, managerJson, type ConversationSession, type WebAgent, type WebVoice } from '../lib/manager'
import { parseBootstrapMessage } from '../lib/bootstrap'
import AccountPanel from './AccountPanel'
import Avatar from './Avatar'
import Composer from './Composer'
import LoginPanel from './LoginPanel'
import Pet from './Pet'
import ReplyBubble from './ReplyBubble'
import { characterKind, characterStyle } from './RoleCharacter'
import { preloadLive2D } from './Live2DCharacter'
import RoleEditor from './RoleEditor'
import RolePicker from './RolePicker'
import type { CharacterMood } from './Character'
import LiveBar from './LiveBar'
import RoleRail, { agentName, dayLabel } from './RoleRail'
import { loadSavedLook, loadSavedOutfit, roleLook, setCustomLook, setOutfit } from '../lib/roles'
import { toMillis } from '../lib/profile'
import { createPlayback, type Playback } from '../lib/playback'
import { createRealtimeClient, type RealtimeEvent } from '../lib/realtime'
import { track } from '../lib/track'
import { createVoiceActivity } from '../lib/voiceActivity'
import { NUDGE_DELAYS_MS, readProactive } from '../lib/companionMode'

/**
 * 管理台的地址。管理台把网页端嵌在自己页面里时，只接受从这些地址发来的登录交接。
 * 可以写多个，用逗号隔开：同一个管理台常常既用 localhost、又用局域网地址或域名打开。
 */
const consoleOrigins = (process.env.NEXT_PUBLIC_CONSOLE_ORIGIN || 'http://127.0.0.1:8001,http://localhost:8001')
  .split(',').map((item) => item.trim()).filter(Boolean)
/** 单个会话服务端最多 100 轮。快到上限时在两轮之间换一个新会话，避免用户那句话撞上限被拒绝。 */
const SESSION_TURN_BUDGET = 90
const MAX_RECONNECT_ATTEMPTS = 6
const RECONNECT_BASE_MS = 1000
const RECONNECT_MAX_MS = 15_000
const AUTO_CONNECT_DELAY_MS = 150
/** 用户关掉了自动朗读。记在浏览器里，下次打开还是关着。 */
const MUTED_KEY = 'companion.muted'
/** 用户在顶栏把角色形象关掉了。记在浏览器里，存的是 '0'。 */
const FIGURE_KEY = 'companion.figure'
/** 连续聊了这么久之后提醒用户休息。中间停了一段时间就重新计时。 */
const LONG_USE_REMINDER_MS = 2 * 60 * 60 * 1000
const LONG_USE_IDLE_RESET_MS = 15 * 60 * 1000
const LONG_USE_CHECK_MS = 60 * 1000

interface AudioClip { bytes: Uint8Array; mimeType: string; text?: string }

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  clips?: AudioClip[]
  image?: string
  /** 角色的回复属于哪一轮。同一轮的文字和语音并在一条消息上。 */
  turn?: string
  /** 什么时候说的（毫秒）。用来在对话里按天分隔。 */
  at?: number
}

/** 一次往上翻读多少轮。和 /api/history 每页的条数一致。 */
const HISTORY_PAGE = 30

/** 按本地时区的日期：2026-11-24。 */
function dayKey(at: number) {
  const date = new Date(at)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** 服务端存的一轮换成页面上的消息：用户那句（开场白没有）和角色的回复。 */
function turnMessages(items: Array<{ text?: unknown; reply?: unknown; turn_id?: unknown; occurred_at?: unknown }>): ChatMessage[] {
  return items.flatMap((item) => {
    if (typeof item.text !== 'string' || typeof item.reply !== 'string') return []
    const at = toMillis(item.occurred_at) ?? undefined
    return [...(item.text ? [{ role: 'user' as const, text: item.text, at }] : []), {
      role: 'assistant' as const, text: item.reply, at,
      // 记下轮次，朗读时服务端按它找到这条回复。
      turn: typeof item.turn_id === 'string' && item.turn_id ? item.turn_id : undefined,
    }]
  })
}

/**
 * 把一轮回复的文字或语音并到这一轮的那条消息上。
 *
 * 文字和语音谁先到都有可能。以前只看最后一条：语音先到就先建了一条没有字的消息，
 * 文字到了又因为“是新的一轮”另建一条，结果一条回复显示成两条，各带一个“重听”。现在按轮次找。
 * 事件不带轮次时（旧的服务端），接在最后一条角色消息后面。
 */
function withReply(current: ChatMessage[], turn: string | undefined, change: (message: ChatMessage) => ChatMessage) {
  if (turn) {
    for (let index = current.length - 1; index >= 0; index -= 1) {
      const item = current[index]
      if (item.role === 'assistant' && item.turn === turn) return [...current.slice(0, index), change(item), ...current.slice(index + 1)]
    }
  } else {
    const last = current[current.length - 1]
    if (last?.role === 'assistant') return [...current.slice(0, -1), change(last)]
  }
  return [...current, change({ role: 'assistant', text: '', turn, at: Date.now() })]
}

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
  return { bytes, mimeType, ...(typeof details.text === 'string' && details.text ? { text: details.text } : {}) }
}

/** 麦克风不可用时给用户看的原因。手机上最常见的是页面不是 HTTPS，或者没有授权。 */
function microphoneProblem(reason: unknown) {
  const name = reason instanceof Error ? reason.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') return '没有麦克风权限，请在浏览器设置里允许后重试'
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return '没有找到可用的麦克风'
  if (name === 'NotReadableError') return '麦克风被其他应用占用了，请关闭后重试'
  return '麦克风打开失败，可以先用文字输入'
}

/** 有没有真正的字。噪声常被识别成一个逗号或句号，那不算用户说了话。 */
function hasWords(text: string) {
  return /[\p{L}\p{N}]/u.test(text)
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
  // 异步读完历史时用来确认角色没换过。
  const agentIdRef = useRef(agentId)
  agentIdRef.current = agentId
  const [voiceId, setVoiceId] = useState('')
  const [messages, setMessages] = useState<ChatMessage[]>([])
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
  // 判断用户是不是在说话、哪些声音要发给服务端。见 lib/voiceActivity.ts。
  const voiceRef = useRef(createVoiceActivity())
  // 通话里最近一次有人说话（用户或角色）的时间，以及用户没接话的情况下角色已经主动找过几次话题。
  const lastActivityAtRef = useRef(0)
  const nudgeCountRef = useRef(0)
  const assistantTurnRef = useRef<string | null>(null)
  /** 正在朗读的那条回复，和已经收到的语音。读完记到消息上。 */
  const readingRef = useRef<{ turn: string; clips: AudioClip[] } | null>(null)
  // 正在播放的声音属于哪一轮；以及用户开口打断了哪一轮，这一轮后面才到的声音不再播。
  const audioTurnRef = useRef<string | null>(null)
  const interruptedTurnRef = useRef<string | null>(null)
  const playbackRef = useRef<Playback | null>(null)
  const messagesRef = useRef<HTMLDivElement>(null)
  // 用户往上翻看之前的消息时，新消息来了不要把页面拽回底部。
  const stickToBottomRef = useRef(true)
  // 往上翻时读更早的对话：最早一条已加载的时间，以及还有没有更早的。
  const oldestAtRef = useRef<number | null>(null)
  const hasOlderRef = useRef(false)
  const loadingOlderRef = useRef(false)
  const [loadingOlder, setLoadingOlder] = useState(false)
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
  const startSessionRef = useRef<() => Promise<void>>(async () => undefined)
  const [reconnectFailed, setReconnectFailed] = useState(false)
  // 今天的对话次数用完了。不再连接也不再重试，等用户明天再来。
  const [quotaExhausted, setQuotaExhausted] = useState(false)
  // 连续使用提醒：从什么时候开始连续在聊、最近一次说话是什么时候。
  const activeSinceRef = useRef<number | null>(null)
  const lastActivityRef = useRef(0)
  const [restReminder, setRestReminder] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  // 正在设置哪个角色。fresh 表示它是刚新建的空白角色。
  const [editing, setEditing] = useState<{ id: string; fresh: boolean } | null>(null)
  // 角色列表是从左边滑出的抽屉，这里是它有没有打开。
  const [railOpen, setRailOpen] = useState(false)
  // 角色一直在页面上：平时缩成桌宠待在对话区右下角，点一下放大站到对话上方（宽屏上在右边）。
  // 通话开始时自己放大，挂断后缩回去。
  const [figureOpen, setFigureOpen] = useState(false)
  // 顶栏的开关把角色整个藏起来：没有桌宠，通话时也不站出来，只剩底部的通话栏。放大缩小还是点角色自己。
  const [figureHidden, setFigureHidden] = useState(false)
  // 角色正在念的那句话，通话时显示在它下面。
  const [caption, setCaption] = useState('')
  // 通话中暂时关掉麦克风：不挂断，只是不收音。
  const [micMuted, setMicMuted] = useState(false)
  const micMutedRef = useRef(false)
  // 麦克风现在的音量，0 到 1。每一帧都在变，所以不放进状态里，舞台上的角色自己来读。
  const micLevelRef = useRef(0)
  // 这一轮到哪了：waiting 是用户说完了、回复还没开始出现（显示“正在输入”），replying 是回复正在出来。
  const [turnPhase, setTurnPhase] = useState<'none' | 'waiting' | 'replying'>('none')
  // 角色的声音是不是正在播放。
  const [voicing, setVoicing] = useState(false)
  // 关掉自动朗读后，回复只显示文字，想听的时候长按回复选“重新朗读”。语音通话不受影响。
  const [muted, setMuted] = useState(false)
  const mutedRef = useRef(false)
  const liveRef = useRef(false)
  liveRef.current = live

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
    // 用户给角色选过的形象记在这个浏览器里，每次读到角色列表时登记一遍。
    for (const item of available) {
      const name = item.agentName || item.name || item.id
      setCustomLook(name, loadSavedLook(item.id))
      setOutfit(name, loadSavedOutfit(item.id))
    }
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
    // 不知道嵌着自己的是哪个地址的管理台，每个都发一遍；地址不对的浏览器会直接丢掉。
    if (window.parent !== window) for (const origin of consoleOrigins) window.parent.postMessage({ type: 'companion.ready' }, origin)
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
      const message = consoleOrigins.includes(event.origin) ? parseBootstrapMessage(event.data, event.origin, event.origin) : null
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
    readingRef.current = null
    const player = playback()
    player.stop()
    for (const clip of clips) player.play(clip.bytes, clip.mimeType, clip.text)
    track('reply_replayed')
  }, [playback])

  /**
   * 朗读一条回复。当场说过的回复本地留着语音，直接再播；从对话记录加载出来的没有语音，请服务端按轮次再合成一次。
   * 合成好的语音记到这条回复上，下次就不用再要。
   */
  const readAloud = useCallback((message: ChatMessage) => {
    if (message.clips?.length) { replay(message.clips); return }
    if (!message.turn || !clientRef.current) return
    playback().stop()
    readingRef.current = { turn: message.turn, clips: [] }
    try {
      clientRef.current.readReply(crypto.randomUUID(), message.turn)
      track('reply_read')
    } catch (reason) {
      readingRef.current = null
      fail(reason)
    }
  }, [fail, playback, replay])

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
        try { clientRef.current.sendText(crypto.randomUUID(), pending.text, pending.images); setStatus('处理中'); setTurnPhase('waiting') } catch { pendingTextRef.current = pending }
      } else if (openingPendingRef.current && clientRef.current) {
        // 隔了一段时间再回来时，角色会先说一句话。重连和换会话不会再请求。
        openingPendingRef.current = false
        try { clientRef.current.requestOpening(crypto.randomUUID()) } catch { /* 没请求成也不影响对话 */ }
      }
      return
    }
    if (event.type === 'asr.partial') { const text = String(details.text || ''); setPartial(hasWords(text) ? text : ''); return }
    if (event.type === 'asr.final') {
      const text = String(details.text || '')
      setPartial('')
      // 噪声常被识别成一个逗号或句号。没有字的不算一句话。
      if (hasWords(text)) { noteActivity(); setTurnPhase('waiting'); setMessages((current) => [...current, { role: 'user', text, at: Date.now() }]) }
      return
    }
    if (event.type === 'reply.withdrawn') {
      // 服务端收回了说到一半的回复：把已经显示的那段换成它给的话，语音也不再保留。
      const text = String(details.text || '')
      track('reply_withdrawn')
      stopPlayback(); setTurnPhase('none')
      setMessages((current) => {
        const last = current[current.length - 1]
        // 没有替换的话（被收回的是角色主动说的开场白）就直接拿掉。
        if (!text) return last?.role === 'assistant' ? current.slice(0, -1) : current
        if (last?.role === 'assistant') return [...current.slice(0, -1), { role: 'assistant', text, turn: last.turn, at: last.at }]
        return [...current, { role: 'assistant', text, at: Date.now() }]
      })
      return
    }
    if (event.type === 'turn.started') {
      // 开始新的一轮时，正在朗读的旧回复停下。
      if (readingRef.current) { readingRef.current = null; stopPlayback() }
      assistantTurnRef.current = event.turn_id || null; turnActiveRef.current = true; setTurnPhase('waiting'); return }
    if (event.type === 'llm.delta') {
      const text = String(details.text || '')
      // 每一轮的回复是一条新消息。角色先开口时，上一条也是角色说的，不能接在它后面。
      setTurnPhase('replying')
      setMessages((current) => withReply(current, event.turn_id || undefined, (item) => ({ ...item, text: item.text + text })))
      return
    }
    if (event.type === 'tts.audio' || event.type === 'tts.audio.chunk') {
      const payload = audioPayload(details)
      if (!payload) return
      if (details.purpose === 'read') {
        // 用户点了朗读：关了自动朗读也要读出来。已经换成读别的了，就不再播这条的。
        const reading = readingRef.current
        if (!reading || reading.turn !== event.turn_id) return
        reading.clips.push(payload)
        playback().play(payload.bytes, payload.mimeType, payload.text)
        return
      }
      // 语音是一句一句到的：到一句排队播一句，同时记在这条回复上，供用户重听。
      setMessages((current) => withReply(current, event.turn_id || undefined, (item) => ({ ...item, clips: [...(item.clips || []), payload] })))
      // 用户已经开口打断了这一轮：后面才到的几句只留着供重听，不再读出来。
      if (event.turn_id && event.turn_id === interruptedTurnRef.current) return
      audioTurnRef.current = event.turn_id || null
      // 关了自动朗读就不播，语音通话除外。
      if (!mutedRef.current || liveRef.current) playback().play(payload.bytes, payload.mimeType, payload.text)
      return
    }
    if (event.type === 'tts.audio.binary') return
    if (event.type === 'reply.read.done' || event.type === 'reply.read.failed') {
      const reading = readingRef.current
      if (!reading || reading.turn !== event.turn_id) return
      readingRef.current = null
      if (event.type === 'reply.read.failed') { fail('这条回复暂时没法朗读'); return }
      if (reading.clips.length) setMessages((current) => withReply(current, reading.turn, (item) => ({ ...item, clips: reading.clips })))
      return
    }
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
      fail(details.message || details.code || '本轮失败'); setStatus('已连接'); setTurnPhase('none'); settleTurn(false); return
    }
    if (event.type === 'turn.completed' || event.type === 'turn.cancelled' || event.type === 'turn.failed') {
      setStatus('已连接'); setTurnPhase('none'); settleTurn(event.type === 'turn.completed')
    }
  }, [fail, noteActivity, playback, settleTurn, stopPlayback])

  const stopCapture = useCallback(async () => {
    processorRef.current?.disconnect(); sourceRef.current?.disconnect()
    mediaStreamRef.current?.getTracks().forEach((track) => track.stop())
    if (audioContextRef.current) await audioContextRef.current.close().catch(() => undefined)
    processorRef.current = null; sourceRef.current = null; mediaStreamRef.current = null; audioContextRef.current = null
    voiceRef.current.reset()
    micLevelRef.current = 0
    micMutedRef.current = false; setMicMuted(false)
    setSpeaking(false)
  }, [])

  /** 今天的次数用完了：停下来，不再自动连接。说到一半的话不会丢，但要等明天。 */
  const stopForQuota = useCallback(() => {
    wantConnectedRef.current = false
    generationRef.current += 1
    if (reconnectTimerRef.current !== null) { window.clearTimeout(reconnectTimerRef.current); reconnectTimerRef.current = null }
    pendingTextRef.current = null
    clientRef.current?.close(); clientRef.current = null
    // 麦克风也要关掉，不能停在通话界面上继续收音。
    void stopCapture()
    setConnected(false); setLive(false); setTurnPhase('none'); setQuotaExhausted(true); setError(''); setStatus('今日次数已用完')
    track('quota_reached')
  }, [stopCapture])
  const stopForQuotaRef = useRef(stopForQuota)
  stopForQuotaRef.current = stopForQuota

  /** 连接意外中断或建立失败后，隔一会儿自动再试；连续失败多次才停下来让用户手动重连。 */
  const scheduleReconnect = useCallback((reason?: unknown) => {
    setConnected(false); setTurnPhase('none')
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
    setReconnectFailed(false)
    void startSessionRef.current()
  }, [])

  // 选好角色（和音色）后自动连接，不需要用户手动点。稍等一下再连，避免角色和音色先后变化时连两次。
  useEffect(() => {
    if (!authenticated || !agentId) return
    wantConnectedRef.current = true
    reconnectAttemptsRef.current = 0
    setReconnectFailed(false); setQuotaExhausted(false)
    const timer = window.setTimeout(() => { void startSessionRef.current() }, AUTO_CONNECT_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [authenticated, agentId, voiceId])

  // 换角色或重新进入时，先显示和这个角色之前聊过的内容。
  useEffect(() => {
    if (!authenticated || !agentId) return
    let active = true
    setMessages([])
    oldestAtRef.current = null; hasOlderRef.current = false
    openingPendingRef.current = true
    managerJson<Array<{ text?: unknown; reply?: unknown; turn_id?: unknown; occurred_at?: unknown }>>(`/history?agentId=${encodeURIComponent(agentId)}`)
      .then((items) => {
        if (!active || !Array.isArray(items)) return
        const earlier = turnMessages(items)
        oldestAtRef.current = items.map((item) => toMillis(item.occurred_at)).find((at) => at != null) ?? null
        hasOlderRef.current = items.length >= HISTORY_PAGE && oldestAtRef.current != null
        // 历史加载期间用户可能已经开始说话，新消息排在历史后面。
        setMessages((current) => [...earlier, ...current])
      })
      .catch(() => undefined)
    return () => { active = false }
  }, [authenticated, agentId])

  /** 读更早的一页，接在最前面。返回有没有读到。翻上去的位置保持不动。 */
  const loadOlder = useCallback(async () => {
    if (!agentId || loadingOlderRef.current || !hasOlderRef.current || oldestAtRef.current == null) return false
    loadingOlderRef.current = true; setLoadingOlder(true)
    const requested = agentId
    try {
      const items = await managerJson<Array<{ text?: unknown; reply?: unknown; turn_id?: unknown; occurred_at?: unknown }>>(
        `/history?agentId=${encodeURIComponent(agentId)}&before=${oldestAtRef.current}`)
      if (requested !== agentIdRef.current || !Array.isArray(items)) return false
      const first = items.map((item) => toMillis(item.occurred_at)).find((at) => at != null)
      if (first != null) oldestAtRef.current = first
      hasOlderRef.current = items.length >= HISTORY_PAGE && first != null
      if (items.length === 0) return false
      const element = messagesRef.current
      const fromBottom = element ? element.scrollHeight - element.scrollTop : 0
      stickToBottomRef.current = false
      setMessages((current) => [...turnMessages(items), ...current])
      // 新内容接在上面，等它画出来再把位置挪回原来看的那句。
      requestAnimationFrame(() => { if (element) element.scrollTop = element.scrollHeight - fromBottom })
      return true
    } catch { return false } finally { loadingOlderRef.current = false; setLoadingOlder(false) }
  }, [agentId])

  /** 从侧栏点了某一天：一页页往前读到那天，再滚到那天开头。最多读二十页。 */
  const jumpToDay = useCallback(async (start: number) => {
    setRailOpen(false)
    for (let page = 0; page < 20 && oldestAtRef.current != null && oldestAtRef.current >= start && hasOlderRef.current; page += 1) {
      if (!await loadOlder()) break
      await new Promise((resolve) => requestAnimationFrame(resolve))
    }
    requestAnimationFrame(() => {
      const element = messagesRef.current
      const divider = element?.querySelector<HTMLElement>(`[data-day="${dayKey(start)}"]`)
      if (!element || !divider) return
      stickToBottomRef.current = false
      element.scrollTop = Math.max(0, divider.offsetTop - 8)
    })
  }, [loadOlder])

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
        // 麦克风关着时什么都不发，也不判断有没有人在说话。
        if (micMutedRef.current) { micLevelRef.current = 0; return }
        const frame = downsample(event.inputBuffer.getChannelData(0), context.sampleRate)
        const rms = Math.sqrt(frame.reduce((total, value) => total + value * value, 0) / Math.max(1, frame.length))
        micLevelRef.current = Math.min(1, rms * 7)
        const roleSpeaking = Boolean(playbackRef.current?.isPlaying())
        const result = voiceRef.current.feed(frame, pcm16(frame), roleSpeaking)
        // 只有确认在说话时才把声音发出去，安静和噪声都留在本地。
        try { for (const chunk of result.send) clientRef.current?.pushAudio(chunk) } catch { return }
        if (result.started) {
          lastActivityAtRef.current = Date.now()
          nudgeCountRef.current = 0
          if (roleSpeaking && playbackRef.current) {
            // 用户开口了，角色正在说：立刻停下。
            // 服务端把语音发完就算这一轮结束了，但声音还要播好几秒，所以不能只在“这一轮还没结束”时才停。
            const playedMs = playbackRef.current.playedMs()
            interruptedTurnRef.current = assistantTurnRef.current ?? audioTurnRef.current
            stopPlayback()
            // 这一轮还在进行的话，告诉服务端别再往下说了。已经结束的不用说。
            if (assistantTurnRef.current && clientRef.current) {
              try { clientRef.current.cancel(String(assistantTurnRef.current), playedMs) } catch { /* 连接刚断，声音已经停了 */ }
            }
            track('reply_interrupted')
          }
          setSpeaking(true)
        }
        if (result.ended !== null) {
          lastActivityAtRef.current = Date.now()
          try { clientRef.current?.commit(crypto.randomUUID(), result.ended) } catch { /* 连接刚断，这句就不要了 */ }
          setSpeaking(false)
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

  /**
   * 结束通话：只是不再收音，连接保留，接着打字不用等。
   * 不能给服务端发 stream.stop，它收到后会把整个连接关掉，页面就会显示“正在重新连接”。
   */
  const endLive = useCallback(async () => {
    const unfinished = voiceRef.current.finish()
    if (unfinished !== null && clientRef.current) {
      // 说到一半挂断：把已经说的这半句交出去。连接刚好断了也不要紧，挂断照常完成。
      try { clientRef.current.commit(crypto.randomUUID(), unfinished) } catch { /* 这半句就不要了 */ }
    }
    // 挂断就是不想再听了，正在读的回复停下来，文字照常显示。
    stopPlayback()
    await stopCapture(); setLive(false); setStatus('已连接')
  }, [stopCapture, stopPlayback])

  /** 发一句话。quick 是点了输入框上方的建议，直接发出去，不动输入框里正在写的内容。 */
  const sendText = useCallback((quick?: string) => {
    const text = (quick ?? draft).trim()
    const images = quick ? [] : imageData
    if (!text && images.length === 0) return
    if (!wantConnectedRef.current) return
    noteActivity()
    stickToBottomRef.current = true
    setMessages((current) => [...current, { role: 'user', text: text || '图片', at: Date.now(), ...(images[0] ? { image: images[0] } : {}) }])
    if (quick) track('starter_used')
    else { setDraft(''); setImageData([]) }
    if (clientRef.current && connected) {
      try { clientRef.current.sendText(crypto.randomUUID(), text, images); setStatus('处理中'); setTurnPhase('waiting'); return } catch { /* 连接刚断开，按重连处理 */ }
    }
    // 正在重连：先把这句话存起来，连上之后自动补发，用户不用重新输入。
    pendingTextRef.current = { text, images }
    if (reconnectTimerRef.current === null && !connected) void startSessionRef.current()
  }, [connected, draft, imageData, noteActivity])

  const disconnect = useCallback(async () => {
    wantConnectedRef.current = false
    generationRef.current += 1
    if (reconnectTimerRef.current !== null) { window.clearTimeout(reconnectTimerRef.current); reconnectTimerRef.current = null }
    pendingTextRef.current = null
    await stopCapture(); clientRef.current?.close(); clientRef.current = null
    setConnected(false); setLive(false); setTurnPhase('none'); setStatus('未连接')
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
    setError(''); setStatus('已退出'); setReconnectFailed(false); setQuotaExhausted(false); setRailOpen(false)
  }, [disconnect, stopPlayback])

  // 新消息到来时滚到底部，除非用户自己往上翻了。
  useEffect(() => {
    const element = messagesRef.current
    if (element && stickToBottomRef.current) element.scrollTop = element.scrollHeight
  }, [messages, partial, turnPhase])

  // 记住上次有没有关掉自动朗读。
  useEffect(() => {
    try {
      if (window.localStorage.getItem(MUTED_KEY) === '1') { mutedRef.current = true; setMuted(true) }
      if (window.localStorage.getItem(FIGURE_KEY) === '0') setFigureHidden(true)
    } catch { /* 浏览器不让用本地存储时按默认处理 */ }
  }, [])

  // 手指在页面上往右滑打开角色列表，往左滑收起。竖着滑动（翻对话）和在可以横向滚动的地方滑动不算。
  const swipeRef = useRef<{ x: number; y: number } | null>(null)
  const onSwipeStart = useCallback((event: React.TouchEvent) => {
    const target = event.target as HTMLElement
    const touch = event.touches[0]
    swipeRef.current = !touch || target.closest('.starters, .sheet-backdrop, .pet, textarea, input, select') ? null : { x: touch.clientX, y: touch.clientY }
  }, [])
  const onSwipeEnd = useCallback((event: React.TouchEvent) => {
    const start = swipeRef.current
    swipeRef.current = null
    const touch = event.changedTouches[0]
    if (!start || !touch) return
    const dx = touch.clientX - start.x
    const dy = touch.clientY - start.y
    if (Math.abs(dx) < 64 || Math.abs(dx) < Math.abs(dy) * 1.8) return
    setRailOpen(dx > 0)
  }, [])

  // 通话开始，角色走到前面来；挂断后缩回角落。通话中用户也可以自己把它缩小。
  useEffect(() => { setFigureOpen(live) }, [live])

  const toggleFigure = useCallback(() => { setFigureOpen((open) => !open) }, [])
  /** 顶栏的开关：显示或藏起角色。重新显示时，通话中它直接站出来，平时回到角落。 */
  const toggleFigureShown = useCallback(() => {
    const next = !figureHidden
    setFigureHidden(next)
    setFigureOpen(liveRef.current)
    try { window.localStorage.setItem(FIGURE_KEY, next ? '0' : '1') } catch { /* 存不了就只在这次生效 */ }
    track('figure_toggled', { on: !next })
  }, [figureHidden])
  /** 用户自己翻对话时，放大的角色让开。通话时不让，那时角色是主角。 */
  const shrinkForReading = useCallback(() => { if (!liveRef.current) setFigureOpen(false) }, [])

  /** 通话中关掉或打开麦克风。关掉时正在说的半句话直接丢掉。 */
  const toggleMic = useCallback(() => {
    const next = !micMutedRef.current
    micMutedRef.current = next; setMicMuted(next)
    if (next) { voiceRef.current.reset(); micLevelRef.current = 0; setSpeaking(false) }
  }, [])

  const toggleMuted = useCallback(() => {
    const next = !mutedRef.current
    mutedRef.current = next; setMuted(next)
    try { window.localStorage.setItem(MUTED_KEY, next ? '1' : '0') } catch { /* 存不了就只在这次生效 */ }
    if (next && !liveRef.current) playbackRef.current?.stop()
    track('autoplay_toggled', { on: !next })
  }, [])

  // 隔一小会儿看一眼角色的声音是不是在播、念到哪一句，用来显示“在说话”和通话里的字幕。
  useEffect(() => {
    const timer = window.setInterval(() => {
      setVoicing(Boolean(playbackRef.current?.isPlaying()))
      setCaption(playbackRef.current?.current() ?? '')
    }, 200)
    return () => window.clearInterval(timer)
  }, [])

  // 页面空闲时提前把当前角色的 Live2D 模型下载好，点通话时角色马上就能出来。
  const preloadName = selectedAgent ? agentName(selectedAgent) : ''
  useEffect(() => {
    if (!preloadName || figureHidden || characterKind(preloadName) !== 'live2d') return
    const scope = window as Window & { requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number; cancelIdleCallback?: (id: number) => void }
    if (scope.requestIdleCallback) {
      const id = scope.requestIdleCallback(() => preloadLive2D(preloadName), { timeout: 4000 })
      return () => scope.cancelIdleCallback?.(id)
    }
    const timer = window.setTimeout(() => preloadLive2D(preloadName), 1500)
    return () => window.clearTimeout(timer)
  }, [preloadName, figureHidden])

  // 陪伴模式：通话里双方都安静了一阵子，问服务端要不要主动说一句。
  useEffect(() => {
    if (!live || !connected) return
    lastActivityAtRef.current = Date.now()
    nudgeCountRef.current = 0
    const timer = window.setInterval(() => {
      const now = Date.now()
      // 角色在说、在想，或者用户正在说：都算还在聊。
      if (playbackRef.current?.isPlaying() || turnActiveRef.current || voiceRef.current.speaking()) {
        lastActivityAtRef.current = now
        return
      }
      if (micMutedRef.current || !readProactive() || !clientRef.current) return
      const count = nudgeCountRef.current
      if (count >= NUDGE_DELAYS_MS.length || now - lastActivityAtRef.current < NUDGE_DELAYS_MS[count]) return
      const idleSeconds = (now - lastActivityAtRef.current) / 1000
      nudgeCountRef.current = count + 1
      lastActivityAtRef.current = now
      try { clientRef.current.requestNudge(crypto.randomUUID(), idleSeconds) } catch { /* 连接刚断，下次再说 */ }
      track('nudge_requested', { count: count + 1 })
    }, 1000)
    return () => window.clearInterval(timer)
  }, [live, connected])

  /** 舞台上的角色每一帧来读一次：角色自己在说时是它声音的音量，用户在说时是麦克风的音量。 */
  const readLevel = useCallback(() => Math.max(playbackRef.current?.level() ?? 0, micLevelRef.current), [])

  // 重连期间仍然可以输入，发出的话会在连上后补发；只有重连彻底失败或今天的次数用完时才停用输入。
  const canType = Boolean(agentId) && !reconnectFailed && !quotaExhausted

  if (!authenticated) {
    if (!sessionChecked) return <Splash text={error || '正在打开'} />
    return <LoginPanel onSignedIn={signedIn} />
  }
  if (!agentsLoaded) {
    return <Splash
      text={error || '正在找你的角色'}
      onRetry={error ? () => { setError(''); void loadAgents().catch(fail) } : undefined}
    />
  }
  // 还没有角色：先选一个。从管理台进来的账号也一样，管理台里已经没有管理自己角色的页面了。
  if (agents.length === 0) {
    return <RolePicker
      onCreated={(id, blank) => {
        track(blank ? 'role_created_blank' : 'role_created')
        void loadAgents().then(() => { if (id && blank) setEditing({ id, fresh: true }) }).catch(fail)
      }}
      onSignOut={() => void signOut()}
    />
  }
  // 已经有角色的用户再添加一个。添加完回到对话，新角色出现在角色列表里。
  if (addingRole) {
    return <RolePicker
      existingNames={agents.map((agent) => agentName(agent))}
      onBack={() => setAddingRole(false)}
      onCreated={(id, blank) => {
        track(blank ? 'role_created_blank' : 'role_added'); setAddingRole(false)
        // 添加完直接切到新角色。空白角色接着打开设置，让用户给它起名字、写性格。
        void loadAgents().then(() => { if (id) { setAgentId(id); if (blank) setEditing({ id, fresh: true }) } }).catch(fail)
      }}
      onSignOut={() => void signOut()}
    />
  }

  // 个人中心是单独的一页，从侧栏最下面进来。
  if (settingsOpen) {
    return <AccountPanel
      roleCount={agents.length}
      account={sessionKind === 'app'}
      onClose={() => setSettingsOpen(false)}
      onSignOut={() => { setSettingsOpen(false); void signOut() }}
      onAccountDeleted={() => { setSettingsOpen(false); void signOut() }}
    />
  }

  // 角色设置是单独的一页。连接和对话都还在，返回就回到原来的地方。
  if (editing) {
    return <RoleEditor
      agentId={editing.id} fresh={editing.fresh}
      agentName={agentName(agents.find((agent) => agent.id === editing.id) ?? { id: editing.id })}
      onClose={() => setEditing(null)}
      onHistoryCleared={() => {
        // 服务端的记录已经清空。正在聊的就是它的话，换一个新会话让它从空白开始，页面上的消息也清掉。
        track('history_cleared')
        if (editing.id !== agentId) return
        setMessages([]); oldestAtRef.current = null; hasOlderRef.current = false
        void startSessionRef.current()
      }}
      onSaved={() => {
        const id = editing.id
        track('role_saved'); setEditing(null)
        // 新的设置已经生效。重新读一遍角色；正在聊的就是它的话，换一个新会话让它用上新的设置。
        void loadAgents().then(() => { if (id === agentId) void startSessionRef.current() }).catch(fail)
      }}
      onDeleted={() => {
        track('role_deleted'); setEditing(null)
        if (live) void endLive()
        // 删掉的是正在聊的角色时，角色列表读回来后会自动换到剩下的第一个；一个都不剩就回到选角色。
        void loadAgents().catch(fail)
      }}
    />
  }

  const roleName = selectedAgent ? agentName(selectedAgent) : '陪伴角色'
  const look = roleLook(roleName)
  const awaiting = turnPhase === 'waiting'
  const hasUserMessage = messages.some((message) => message.role === 'user')
  const mood: CharacterMood = !connected ? 'offline' : speaking ? 'hearing' : voicing ? 'speaking' : turnPhase !== 'none' ? 'thinking' : 'idle'
  const stateText = !connected ? status
    : speaking ? '在听你说…'
    : voicing ? (live ? '在说话，可以随时打断' : '在说话…')
    : turnPhase !== 'none' ? '在想…'
    : live ? '通话中，直接说话就可以' : '在线'
  const starters = hasUserMessage || !connected || !canType || live ? [] : look.starters
  // 会动的角色可以整个停用（见 RoleCharacter），用户也可以在顶栏把它藏起来。都没有时通话只有底部的声浪。
  const figureAvailable = characterStyle() !== 'none'
  const figure = figureAvailable && !figureHidden

  return <div
    className={`app ${live ? 'is-live' : ''} ${figure ? 'has-pet' : ''} ${figure && figureOpen ? 'figure-open' : ''}`}
    onTouchStart={onSwipeStart} onTouchEnd={onSwipeEnd}
    style={{ '--aura-1': look.aura[0], '--aura-2': look.aura[1], '--aura-3': look.aura[2] } as React.CSSProperties}
  >
    <div className="ambient" aria-hidden="true" />
    <header className="topbar">
      <button type="button" className="glass-button" aria-label="打开侧栏" aria-expanded={railOpen} onClick={() => setRailOpen(true)}><Menu size={22} /></button>
      <div className="who">
        <h1>{roleName}</h1>
        <span className={`presence ${connected ? 'on' : ''}`} role="status">{stateText}</span>
      </div>
      <div className="glass-group">
        {figureAvailable && <button
          type="button" onClick={toggleFigureShown} aria-pressed={!figureHidden}
          aria-label={figureHidden ? '显示角色形象' : '隐藏角色形象'} title={figureHidden ? '角色形象已隐藏' : '角色形象显示在对话区'}
        ><Smile size={21} /></button>}
        <button
          type="button" onClick={toggleMuted} aria-pressed={!muted}
          aria-label={muted ? '打开自动朗读' : '关闭自动朗读'} title={muted ? '回复只显示文字' : '回复会自动读出来'}
        >{muted ? <VolumeX size={21} /> : <Volume2 size={21} />}</button>
      </div>
    </header>
    <div className="main">
      {/* 展开时给角色留出的地方。角色本身在 Pet 里，收起时它缩成桌宠浮在对话区，这块地方让给对话。 */}
      <section className={`figure-slot ${figure && figureOpen ? 'open' : ''}`} aria-label={`${roleName}的形象`} aria-hidden={!(figure && figureOpen)}>
        <button type="button" className="figure-close" aria-label="缩小" tabIndex={figure && figureOpen ? 0 : -1} onClick={toggleFigure}><Minimize2 size={17} /></button>
        {/* 通话时角色下面的字幕：用户在说就显示他说的，角色在说就显示正在念的那一句。 */}
        {figure && figureOpen && live && (speaking ? partial : caption) && <p className={`caption ${speaking ? 'user' : ''}`} aria-hidden="true">{speaking ? partial : caption}</p>}
      </section>
      {figure && <Pet name={roleName} mood={mood} level={readLevel} open={figureOpen} live={live} onOpen={toggleFigure} onClose={toggleFigure} />}
      <div className="chat-col">
        <div className="notices">
          {error && <div className="notice error" role="alert">
            <span>{error}</span>
            <button type="button" className="notice-close" aria-label="关闭提示" onClick={() => setError('')}><X size={16} /></button>
          </div>}
          {reconnectFailed && <div className="notice" role="status">
            <span>连接断开了，检查一下网络再试。</span>
            <button type="button" className="notice-action" onClick={connect}><RotateCw size={14} />重新连接</button>
          </div>}
          {restReminder && <div className="notice" role="status">
            <span>已经连续聊了两个小时啦，起来活动一下、喝点水，休息一会儿再聊吧。</span>
            <button type="button" className="notice-action" onClick={() => { setRestReminder(false); activeSinceRef.current = Date.now() }}>知道了</button>
          </div>}
          {quotaExhausted && <div className="notice" role="status">今天聊得够多啦，明天再来找{roleName}吧。之前聊过的内容都还在。</div>}
        </div>
        <div
          key={agentId} className="messages" role="log" aria-live="polite" ref={messagesRef}
          onWheel={shrinkForReading} onTouchMove={shrinkForReading}
          onScroll={(event) => {
            const element = event.currentTarget
            stickToBottomRef.current = element.scrollHeight - element.scrollTop - element.clientHeight < 96
            // 翻到最上面附近就读更早的一页。
            if (element.scrollTop < 120 && hasOlderRef.current) void loadOlder()
          }}
        >
          {loadingOlder && <p className="history-more" role="status">正在读更早的对话</p>}
          {messages.length === 0 && <div className="empty-conversation">
            <p>想和{roleName}聊点什么？</p>
            <span>可以打字，也可以点右下角直接开口说</span>
          </div>}
          {messages.map((message, index) => {
            // 语音已经到了、文字还没到的回复先不显示，免得出现一个空气泡。
            if (!message.text && !message.image) return null
            // 同一个人连着说的几句，只在第一句旁边放头像。
            const first = index === 0 || messages[index - 1].role !== message.role
            // eslint-disable-next-line @next/next/no-img-element
            const image = message.image && <img className="message-image" src={message.image} alt="已发送图片" />
            // 和上一条不是同一天时，前面放一个日期。
            const previousAt = messages.slice(0, index).reverse().find((item) => item.at != null)?.at
            const day = message.at != null && (previousAt == null || dayKey(previousAt) !== dayKey(message.at)) ? dayKey(message.at) : ''
            return <Fragment key={index}>
            {day && <p className="day-divider" data-day={day}><span>{dayLabel(day)}</span></p>}
            <article className={`message ${message.role}${first ? ' first' : ''}`}>
              {message.role === 'assistant' && (first
                ? <Avatar name={roleName} size={30} className="message-avatar" />
                : <span className="message-avatar" aria-hidden="true" />)}
              <div className="message-column">
                <span className="sr-only">{message.role === 'user' ? '你说：' : `${roleName}说：`}</span>
                {message.role === 'assistant'
                  ? <ReplyBubble text={message.text} onRead={message.clips?.length || (message.turn && connected) ? () => readAloud(message) : undefined}>{image}</ReplyBubble>
                  : <div className="message-body">{message.text}{image}</div>}
              </div>
            </article>
            </Fragment>
          })}
          {partial && <article className="message user partial first">
            <div className="message-column"><div className="message-body">{partial}</div></div>
          </article>}
          {awaiting && connected && <article className="message assistant typing first" aria-label={`${roleName}正在输入`}>
            <Avatar name={roleName} size={30} className="message-avatar" />
            <div className="message-column"><div className="message-body"><i /><i /><i /></div></div>
          </article>}
        </div>
        {starters.length > 0 && <div className="starters" aria-label="可以这样开始">
          {starters.map((text) => <button key={text} type="button" className="starter" onClick={() => sendText(text)}>{text}</button>)}
        </div>}
        <div className="dock">
          {live
            ? <LiveBar
              label={stateText} level={readLevel} aura={look.aura}
              muted={micMuted} onToggleMute={toggleMic}
              onEnd={() => void endLive()}
            />
            : <Composer
              draft={draft} onDraft={setDraft}
              image={imageData[0] ?? null} onImage={(dataUrl) => setImageData(dataUrl ? [dataUrl] : [])} onProblem={fail}
              canType={canType} connected={connected} placeholder={`和${roleName}说点什么`}
              onSend={() => sendText()} onStartVoice={() => void startCapture()}
            />}
        </div>
        <p className="ai-disclosure">{roleName}是 AI，不是真人。回复由 AI 生成，可能不准确。</p>
      </div>
    </div>
    <RoleRail
      agents={agents} agentId={agentId} open={railOpen} canAdd
      onSelect={(id) => {
        setRailOpen(false)
        if (id === agentId) return
        // 换角色就是换一段对话，正在进行的通话先结束。
        if (live) void endLive()
        setAgentId(id)
      }}
      onAdd={() => { setRailOpen(false); if (live) void endLive(); setAddingRole(true) }}
      onClose={() => setRailOpen(false)}
      onJump={(start) => void jumpToDay(start)}
      onProfile={() => { setRailOpen(false); setSettingsOpen(true) }}
      onEdit={(id) => { setRailOpen(false); setEditing({ id, fresh: false }) }}
    />
  </div>
}

/** 还不能显示对话时的占位：正在检查登录、正在加载角色，或者加载失败。 */
function Splash({ text, onRetry }: { text: string; onRetry?: () => void }) {
  return <main className="splash">
    <span className="splash-orb" aria-hidden="true" />
    <p role={onRetry ? 'alert' : 'status'}>{text}</p>
    {onRetry && <button type="button" className="button" onClick={onRetry}>重试</button>}
  </main>
}
