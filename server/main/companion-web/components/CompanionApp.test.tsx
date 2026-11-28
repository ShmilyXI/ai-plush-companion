import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CompanionApp from './CompanionApp'

// 测试环境里没有声卡。这里只记下页面要求播放了什么、按什么顺序。
const audio = vi.hoisted(() => ({ played: [] as string[], texts: [] as string[], stops: 0, playing: false, current: '' }))
vi.mock('../lib/playback', () => ({
  createPlayback: () => ({
    unlock() {},
    play(bytes: Uint8Array, mimeType: string, text?: string) {
      audio.played.push(`${mimeType}:${new TextDecoder().decode(bytes)}`)
      if (text) audio.texts.push(text)
    },
    stop() { audio.stops += 1; audio.playing = false },
    isPlaying: () => audio.playing,
    level: () => 0,
    playedMs: () => 0,
    current: () => audio.current,
    dispose() {},
  }),
}))

/** 对话记录。舞台上的字幕会重复最近的一句话，所以查说过的话要限定在对话记录里。 */
const chat = () => within(screen.getByRole('log'))
const said = async (text: string | RegExp) => within(await screen.findByRole('log')).findByText(text)

/** 测试环境没有麦克风和声卡：只模拟到能打开、能关掉。返回的函数用来检查麦克风有没有被关掉。 */
function stubMicrophone() {
  const stopTrack = vi.fn()
  vi.stubGlobal('navigator', Object.assign(Object.create(navigator), {
    mediaDevices: { getUserMedia: vi.fn(async () => ({ getTracks: () => [{ stop: stopTrack }] })) },
  }))
  const node = () => ({ connect() {}, disconnect() {}, addEventListener() {}, gain: { value: 1 } })
  const listeners: Array<(event: unknown) => void> = []
  vi.stubGlobal('AudioContext', class {
    sampleRate = 16_000
    destination = {}
    createMediaStreamSource() { return node() }
    createScriptProcessor() {
      return { ...node(), addEventListener(_type: string, listener: (event: unknown) => void) { listeners.push(listener) } }
    }
    createGain() { return node() }
    async close() {}
  })
  /** 往麦克风里送一帧声音。volume 是每个采样的幅度，0.3 相当于有人在说话。 */
  const hear = (volume: number) => {
    const frame = new Float32Array(2048).fill(volume)
    for (const listener of listeners) listener({ inputBuffer: { getChannelData: () => frame } })
  }
  return Object.assign(stopTrack, { hear })
}

class FakeWebSocket {
  static OPEN = 1
  static CONNECTING = 0
  readyState = FakeWebSocket.OPEN
  binaryType = ''
  listeners = new Map<string, Array<(event: MessageEvent | Event) => void>>()
  static instances: FakeWebSocket[] = []
  sent: Array<Record<string, unknown>> = []
  constructor(readonly url: string, readonly protocols: string[]) { FakeWebSocket.instances.push(this) }
  addEventListener(type: string, listener: (event: MessageEvent | Event) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }
  /** 发出去的声音帧数。 */
  binary = 0
  send(value: unknown) {
    if (typeof value !== 'string') { this.binary += 1; return }
    const payload = JSON.parse(value) as { type?: string; request_id?: string }
    this.sent.push(payload)
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

/**
 * 用假时钟模拟十几分钟到两个小时的测试，每一秒的定时器都要真的跑一遍、重新渲染，机器忙时要好几秒。
 * 默认 5 秒超时会把它们中途杀掉，而被杀的测试还在后台跑，它最后的 useRealTimers 会打断下一个测试。
 */
const SIMULATED_TIME_TIMEOUT_MS = 30_000

describe('CompanionApp', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs() })

  beforeEach(() => {
    FakeWebSocket.instances = []
    audio.played = []; audio.texts = []; audio.stops = 0; audio.playing = false; audio.current = ''
    // 测试环境没有本地存储，用一个只在内存里的代替。每个测试从空的开始。
    const stored = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => stored.get(key) ?? null,
      setItem: (key: string, value: string) => { stored.set(key, String(value)) },
      removeItem: (key: string) => { stored.delete(key) },
      clear: () => stored.clear(),
    })
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
    expect(await screen.findByRole('heading', { name: '小夏' })).toBeVisible()
    expect(screen.getByRole('button', { name: '小夏' })).toHaveAttribute('aria-current', 'true')
    // 选好角色后自动连接，不需要手动点“连接”。
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    fireEvent.change(screen.getByRole('textbox', { name: '输入消息' }), { target: { value: '你好' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await said('你好，我在线。')).toBeVisible()
  })

  it('shows what was said before and reconnects by itself when the connection drops', async () => {
    const defaultFetch = globalThis.fetch
    let sessions = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.startsWith('/api/history')) return new Response(JSON.stringify({ data: [
        { turn_id: 't1', text: '我的猫叫年糕', reply: '年糕这名字真可爱' },
      ] }), { status: 200 })
      if (url === '/api/conversations') sessions += 1
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)

    expect(await said('我的猫叫年糕')).toBeVisible()
    expect(chat().getByText('年糕这名字真可爱')).toBeVisible()
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    expect(sessions).toBe(1)

    // 连接意外断开：不需要用户做任何事，稍后自动连上，期间仍然可以输入。
    FakeWebSocket.instances[0].emitClose()
    await waitFor(() => expect(screen.getByText('正在重新连接')).toBeVisible())
    expect(screen.getByRole('textbox', { name: '输入消息' })).toBeEnabled()
    fireEvent.change(screen.getByRole('textbox', { name: '输入消息' }), { target: { value: '你还在吗' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await said('你还在吗')).toBeVisible()

    await waitFor(() => expect(sessions).toBe(2), { timeout: 4000 })
    // 断线期间发的那句话，连上之后自动补发。
    await waitFor(() => expect(FakeWebSocket.instances[1].sent.some(
      (payload) => payload.type === 'turn.text' && payload.text === '你还在吗')).toBe(true))
    expect(await said('你好，我在线。')).toBeVisible()
  })

  it('asks the role to speak first once per visit and shows openings it said before', async () => {
    const defaultFetch = globalThis.fetch
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).startsWith('/api/history')) return new Response(JSON.stringify({ data: [
        { turn_id: 't1', text: '我明天要去面试', reply: '会顺利的' },
        { turn_id: 't2', text: '', reply: '面试怎么样了？', opening: true },
      ] }), { status: 200 })
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)

    // 之前的开场白只有角色这一边，不会多出一条空的用户消息。
    expect(await said('面试怎么样了？')).toBeVisible()
    expect(document.querySelectorAll('article.message.user')).toHaveLength(1)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    const openings = (socket: FakeWebSocket) => socket.sent.filter((payload) => payload.type === 'conversation.opening')
    expect(openings(FakeWebSocket.instances[0])).toHaveLength(1)

    // 角色先开口：像普通回复一样显示出来。
    FakeWebSocket.instances[0].emit({ type: 'turn.started', turn_id: 'turn-o', details: { input_mode: 'opening' } })
    FakeWebSocket.instances[0].emit({ type: 'llm.delta', turn_id: 'turn-o', details: { text: '今天过得怎么样？' } })
    FakeWebSocket.instances[0].emit({ type: 'turn.completed', turn_id: 'turn-o', details: { text: '今天过得怎么样？', opening: true } })
    expect(await said('今天过得怎么样？')).toBeVisible()

    // 断线重连是同一次访问，不再请求。
    FakeWebSocket.instances[0].emitClose()
    await waitFor(() => expect(FakeWebSocket.instances.length).toBe(2), { timeout: 4000 })
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    expect(openings(FakeWebSocket.instances[1])).toHaveLength(0)
  })

  it('takes back an opening the server withdraws without leaving an empty bubble', async () => {
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    const socket = FakeWebSocket.instances[0]
    socket.emit({ type: 'turn.started', turn_id: 'turn-o', details: { input_mode: 'opening' } })
    socket.emit({ type: 'llm.delta', turn_id: 'turn-o', details: { text: '不该出现的开场白' } })
    expect(await said('不该出现的开场白')).toBeVisible()

    socket.emit({ type: 'reply.withdrawn', turn_id: 'turn-o', details: { text: '' } })
    socket.emit({ type: 'turn.cancelled', turn_id: 'turn-o', details: { reason: 'opening_failed' } })

    await waitFor(() => expect(chat().queryByText('不该出现的开场白')).not.toBeInTheDocument())
    expect(document.querySelectorAll('article.message')).toHaveLength(0)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('swaps to a fresh session when the runtime token is about to expire', async () => {
    let sessions = 0
    const defaultFetch = globalThis.fetch
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/conversations') sessions += 1
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    const first = FakeWebSocket.instances[0]
    let closed = false
    first.addEventListener('close', () => { closed = true })

    first.emit({ type: 'session.expiring', sequence: 9, details: { expires_at: 0 } })

    await waitFor(() => expect(sessions).toBe(2))
    await waitFor(() => expect(closed).toBe(true))
    // 换会话对用户不可见：没有报错，状态仍然是已连接，可以直接继续说话。
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '输入消息' }), { target: { value: '继续聊' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(FakeWebSocket.instances[1].sent.some(
      (payload) => payload.type === 'turn.text' && payload.text === '继续聊')).toBe(true))
  })

  it('offers a few lines to start with and shows that the role is thinking', async () => {
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    const socket = FakeWebSocket.instances[0]

    // 开口说和打字两种方式都在输入栏里：写了内容才出现发送，语音通话的按钮一直在。
    expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '发送' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '输入消息' }), { target: { value: '写到一半' } })
    expect(screen.getByRole('button', { name: '发送' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled()

    // 角色在准备回复时，名字下面显示“在想”，对话里有三个点；回复开始出现就换成回复本身。
    socket.emit({ type: 'turn.started', turn_id: 'turn-o', details: { input_mode: 'opening' } })
    expect(await screen.findByLabelText('小夏正在输入')).toBeVisible()
    expect(screen.getByRole('status')).toHaveTextContent('在想…')
    socket.emit({ type: 'llm.delta', turn_id: 'turn-o', details: { text: '你来啦。' } })
    await waitFor(() => expect(screen.queryByLabelText('小夏正在输入')).not.toBeInTheDocument())
    socket.emit({ type: 'turn.completed', turn_id: 'turn-o', details: { text: '你来啦。', opening: true } })
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('在线'))

    // 还没说过话：点一句建议直接发出去，输入框里写到一半的内容不动。之后建议不再出现。
    fireEvent.click(await screen.findByRole('button', { name: '随便聊聊' }))
    expect(socket.sent.filter((item) => item.type === 'turn.text').map((item) => item.text)).toEqual(['随便聊聊'])
    expect(await said('你好，我在线。')).toBeVisible()
    expect(screen.getByRole('textbox', { name: '输入消息' })).toHaveValue('写到一半')
    expect(screen.queryByRole('button', { name: '给我讲个冷知识' })).not.toBeInTheDocument()
  })

  it('lists the days talked with the current role in the drawer and jumps back to one, reading older pages on the way', async () => {
    const defaultFetch = globalThis.fetch
    const day = 86_400_000
    const today = new Date(); today.setHours(12, 0, 0, 0)
    const twoDaysAgo = new Date(today.getTime() - 2 * day); twoDaysAgo.setHours(0, 0, 0, 0)
    const key = (at: number) => { const d = new Date(at); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
    // 第一页是今天的 30 轮，更早的一页是两天前的。
    const latest = Array.from({ length: 30 }, (_, index) => ({ turn_id: `t${index + 10}`, text: `今天第${index + 1}句`, reply: '嗯', occurred_at: today.getTime() + index * 1000 }))
    const older = [{ turn_id: 't1', text: '前天聊的面试', reply: '会顺利的', occurred_at: String(twoDaysAgo.getTime() + 9 * 3_600_000) }]
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.startsWith('/api/history/days')) return new Response(JSON.stringify({ data: [
        { day: key(today.getTime()), start: new Date(today).setHours(0, 0, 0, 0), turns: 30, preview: '今天第1句' },
        { day: key(twoDaysAgo.getTime()), start: String(twoDaysAgo.getTime()), turns: '1', preview: '前天聊的面试' },
      ] }), { status: 200 })
      if (url.startsWith('/api/history')) {
        requests.push(url)
        return new Response(JSON.stringify({ data: url.includes('before=') ? older : latest }), { status: 200 })
      }
      if (url === '/api/session') return new Response(JSON.stringify({ data: { phone: '13900000001', sessionKind: 'app' } }), { status: 200 })
      if (url === '/api/plan') return new Response(JSON.stringify({ data: { name: '内测版', maxRoles: 5 } }), { status: 200 })
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)
    expect(await said('今天第30句')).toBeVisible()
    expect(chat().queryByText('前天聊的面试')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '打开侧栏' }))

    // 上面一排是角色，下面是当前角色和它的设置入口、按天列出聊过的日子，最下面是自己的账号。
    expect(screen.getByText('Mowa')).toBeVisible()
    expect(screen.getByRole('navigation', { name: '角色' })).toBeVisible()
    expect(screen.getByRole('button', { name: '设置小夏' })).toBeVisible()
    const days = await screen.findByRole('list', { name: '和小夏聊过的日子' })
    expect(within(days).getByText('今天')).toBeVisible()
    expect(await screen.findByText('139****0001')).toBeVisible()
    expect(screen.getByText('内测版 · 角色 1/5')).toBeVisible()

    // 点前天：往前读一页，前天的内容和日期出现在对话里。
    fireEvent.click(within(days).getByText('前天聊的面试'))
    expect(await said('前天聊的面试')).toBeVisible()
    expect(requests.some((url) => url.includes(`before=${today.getTime()}`))).toBe(true)
    expect(document.querySelector(`.day-divider[data-day="${key(twoDaysAgo.getTime())}"]`)).not.toBeNull()
    expect(document.querySelector(`.day-divider[data-day="${key(today.getTime())}"]`)).not.toBeNull()
  })

  it('opens the side drawer with a swipe to the right and closes it with a swipe to the left', async () => {
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    const menu = screen.getByRole('button', { name: '打开侧栏' })
    const log = screen.getByRole('log')
    const swipe = (fromX: number, fromY: number, toX: number, toY: number) => {
      fireEvent.touchStart(log, { touches: [{ clientX: fromX, clientY: fromY }] })
      fireEvent.touchEnd(log, { changedTouches: [{ clientX: toX, clientY: toY }] })
    }

    // 竖着翻对话不会打开。
    swipe(120, 300, 150, 520)
    expect(menu).toHaveAttribute('aria-expanded', 'false')
    swipe(60, 300, 220, 320)
    expect(menu).toHaveAttribute('aria-expanded', 'true')
    swipe(240, 300, 80, 310)
    expect(menu).toHaveAttribute('aria-expanded', 'false')
  })

  it('stops the role the moment the user starts talking, even after the turn itself has finished', async () => {
    const microphone = stubMicrophone()
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '开始实时通话' }))
    await screen.findByRole('group', { name: '语音通话' })
    const socket = FakeWebSocket.instances[0]
    const cancels = () => socket.sent.filter((item) => item.type === 'response.cancel')
    const chunk = (turn: string, text: string, index: number) =>
      socket.emit({ type: 'tts.audio.chunk', turn_id: turn, details: { mime_type: 'audio/mpeg', data: btoa(text), chunk_index: index } })

    // 第一种情况：服务端已经把这一轮的语音发完、这一轮结束了，但声音还在播。这时开口也要立刻停。
    socket.emit({ type: 'turn.started', turn_id: 'turn-a', details: {} })
    socket.emit({ type: 'llm.delta', turn_id: 'turn-a', details: { text: '第一句。第二句。' } })
    chunk('turn-a', 'one', 0)
    socket.emit({ type: 'turn.completed', turn_id: 'turn-a', details: { text: '第一句。第二句。' } })
    audio.playing = true
    audio.stops = 0
    // 开口要持续一小会儿（这里一帧是 128 毫秒）才算数，一下爆音不会打断角色。
    microphone.hear(0.3)
    expect(audio.stops).toBe(0)
    microphone.hear(0.3)
    expect(audio.stops).toBe(1)
    // 这一轮已经结束，不用再让服务端取消。
    expect(cancels()).toHaveLength(0)

    // 第二种情况：这一轮还在进行。停下声音，并让服务端别再往下说；之后才到的几句不再读出来。
    microphone.hear(0)
    for (let frames = 0; frames < 8; frames += 1) microphone.hear(0)
    socket.emit({ type: 'turn.started', turn_id: 'turn-b', details: {} })
    socket.emit({ type: 'llm.delta', turn_id: 'turn-b', details: { text: '还没说完。' } })
    chunk('turn-b', 'two', 0)
    audio.playing = true
    audio.stops = 0
    audio.played = []
    microphone.hear(0.3)
    microphone.hear(0.3)
    expect(audio.stops).toBe(1)
    expect(cancels().map((item) => item.turn_id)).toEqual(['turn-b'])
    chunk('turn-b', 'three', 1)
    expect(audio.played).toEqual([])
    // 下一轮的声音照常播。
    socket.emit({ type: 'turn.started', turn_id: 'turn-c', details: {} })
    socket.emit({ type: 'llm.delta', turn_id: 'turn-c', details: { text: '好，你说。' } })
    chunk('turn-c', 'four', 0)
    expect(audio.played).toEqual(['audio/mpeg:four'])
  })

  it('keeps noise on the device and only sends sentences the user really spoke', async () => {
    const microphone = stubMicrophone()
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '开始实时通话' }))
    await screen.findByRole('group', { name: '语音通话' })
    const socket = FakeWebSocket.instances[0]
    const commits = () => socket.sent.filter((item) => item.type === 'input.audio.commit')

    // 安静的时候什么都不发；一下爆音（咳嗽、敲桌子）之后又安静了：也什么都不发，不算一句话。
    for (let frames = 0; frames < 10; frames += 1) microphone.hear(0)
    microphone.hear(0.4)
    for (let frames = 0; frames < 10; frames += 1) microphone.hear(0)
    expect(socket.binary).toBe(0)
    expect(commits()).toHaveLength(0)
    expect(screen.queryByText('在听你说…')).not.toBeInTheDocument()

    // 真的说话：开口前的一小段和说的话一起发出去，停顿后交给服务端识别。
    for (let frames = 0; frames < 6; frames += 1) microphone.hear(0.3)
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('在听你说…'))
    for (let frames = 0; frames < 8; frames += 1) microphone.hear(0)
    expect(socket.binary).toBeGreaterThan(6)
    expect(commits()).toHaveLength(1)

    // 识别出来只有一个逗号的不当成用户说的话。
    socket.emit({ type: 'asr.final', details: { text: '，' } })
    socket.emit({ type: 'asr.final', details: { text: '今天好累' } })
    expect(await said('今天好累')).toBeVisible()
    expect(chat().queryByText('，')).not.toBeInTheDocument()
  })

  it('asks whether to bring something up after a quiet spell in a call, backs off, and can be switched off', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const microphone = stubMicrophone()
      const first = render(<CompanionApp />)
      await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
      fireEvent.click(screen.getByRole('button', { name: '开始实时通话' }))
      await screen.findByRole('group', { name: '语音通话' })
      const socket = FakeWebSocket.instances[0]
      const nudges = () => socket.sent.filter((item) => item.type === 'conversation.nudge')

      // 安静 30 秒问一次，再安静 1 分钟、2 分钟各问一次，之后用户不接话就不再问。
      await vi.advanceTimersByTimeAsync(29_000)
      expect(nudges()).toHaveLength(0)
      await vi.advanceTimersByTimeAsync(2_000)
      expect(nudges()).toHaveLength(1)
      expect(nudges()[0].idle_seconds).toBeGreaterThanOrEqual(30)
      await vi.advanceTimersByTimeAsync(61_000)
      expect(nudges()).toHaveLength(2)
      await vi.advanceTimersByTimeAsync(121_000)
      expect(nudges()).toHaveLength(3)
      await vi.advanceTimersByTimeAsync(600_000)
      expect(nudges()).toHaveLength(3)

      // 用户开口后重新开始算。
      microphone.hear(0.3); microphone.hear(0.3)
      for (let frames = 0; frames < 8; frames += 1) microphone.hear(0)
      await vi.advanceTimersByTimeAsync(31_000)
      expect(nudges()).toHaveLength(4)

      // 在设置里关掉“主动找话题”后不再问。
      first.unmount()
      window.localStorage.setItem('companion.proactive', '0')
      render(<CompanionApp />)
      await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
      fireEvent.click(screen.getByRole('button', { name: '开始实时通话' }))
      await screen.findByRole('group', { name: '语音通话' })
      await vi.advanceTimersByTimeAsync(300_000)
      const latest = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
      expect(latest.sent.filter((item) => item.type === 'conversation.nudge')).toHaveLength(0)
    } finally {
      vi.useRealTimers()
    }
  }, SIMULATED_TIME_TIMEOUT_MS)

  it('uses Live2D when it is switched on and falls back to a still picture where it cannot be drawn', async () => {
    vi.stubEnv('NEXT_PUBLIC_CHARACTER_STYLE', 'live2d')
    stubMicrophone()
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '开始实时通话' }))

    // 测试环境没有 WebGL，画不了 Live2D：角色的位置上显示一张静态的图，通话照常。
    expect(await screen.findByRole('region', { name: '小夏的形象' })).toBeVisible()
    await waitFor(() => expect(document.querySelector('.character.live2d.failed')).not.toBeNull())
    expect(screen.getByRole('group', { name: '语音通话' })).toBeVisible()
  })

  it('can hold a call without any animated role', async () => {
    vi.stubEnv('NEXT_PUBLIC_CHARACTER_STYLE', 'none')
    stubMicrophone()
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '开始实时通话' }))

    // 关掉会动的角色：通话照常，只有底部的通话栏。
    expect(await screen.findByRole('group', { name: '语音通话' })).toBeVisible()
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /角色形象/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '放大小夏' })).not.toBeInTheDocument()
  })

  it('keeps the role in the corner, enlarges it on a tap, and puts it back on a second tap or when the user scrolls', async () => {
    stubMicrophone()
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())

    // 没在通话时角色也在：缩成桌宠待在角落，用的是它自己的 2D 图，不是 Live2D 示例模型。
    const pet = screen.getByRole('button', { name: '放大小夏' })
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()
    expect(document.querySelector('.pet .puppet')).not.toBeNull()
    expect(document.querySelector('.pet .character.live2d')).toBeNull()

    // 拖动：按下、移动一段、松开。松开时浏览器还会补一次点击，那一次不能把它放大。
    fireEvent.pointerDown(pet, { pointerId: 1, clientX: 300, clientY: 400 })
    fireEvent.pointerMove(pet, { pointerId: 1, clientX: 180, clientY: 320 })
    fireEvent.pointerUp(pet, { pointerId: 1, clientX: 180, clientY: 320 })
    fireEvent.click(pet)
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()

    // 点一下放大到对话上方。放大后点角色是戳它一下，不会缩回去；缩回去用右上角的「缩小」。
    fireEvent.click(screen.getByRole('button', { name: '放大小夏' }))
    expect(screen.getByRole('region', { name: '小夏的形象' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '戳一下小夏' }))
    fireEvent.click(screen.getByRole('button', { name: '戳一下小夏' }))
    expect(screen.getByRole('region', { name: '小夏的形象' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '缩小' }))
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()

    // 放大着的时候翻对话，角色不缩，只有对话在滚。
    fireEvent.click(screen.getByRole('button', { name: '放大小夏' }))
    fireEvent.wheel(screen.getByRole('log'))
    fireEvent.touchMove(screen.getByRole('log'))
    expect(screen.getByRole('region', { name: '小夏的形象' })).toBeVisible()
  })

  it('hides and shows the role from the top bar, apart from enlarging it, and remembers the choice', async () => {
    stubMicrophone()
    const first = render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())

    // 顶栏的开关只管显示和隐藏；放大缩小是点角色自己，开关不跟着变。
    const toggle = screen.getByRole('button', { name: '隐藏角色形象' })
    expect(toggle).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: '放大小夏' }))
    expect(screen.getByRole('region', { name: '小夏的形象' })).toBeVisible()
    expect(toggle).toHaveAttribute('aria-pressed', 'true')

    // 隐藏：放大的角色和桌宠都不见了。
    fireEvent.click(toggle)
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '放大小夏' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '显示角色形象' })).toHaveAttribute('aria-pressed', 'false')

    // 记在浏览器里，下次打开还是藏着。
    first.unmount()
    render(<CompanionApp />)
    expect(await screen.findByRole('button', { name: '显示角色形象' })).toBeVisible()
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    expect(screen.queryByRole('button', { name: '放大小夏' })).not.toBeInTheDocument()

    // 藏着的时候通话，角色也不站出来，只有通话栏。
    fireEvent.click(screen.getByRole('button', { name: '开始实时通话' }))
    expect(await screen.findByRole('group', { name: '语音通话' })).toBeVisible()
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()

    // 通话中重新显示，角色直接站到前面；挂断后回到角落。
    fireEvent.click(screen.getByRole('button', { name: '显示角色形象' }))
    expect(screen.getByRole('region', { name: '小夏的形象' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '结束实时通话' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '放大小夏' })).toBeInTheDocument())
  })

  it('keeps replies silent when reading aloud is turned off, and still lets the user replay them', async () => {
    const first = render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    fireEvent.click(screen.getByRole('button', { name: '关闭自动朗读' }))
    expect(screen.getByRole('button', { name: '打开自动朗读' })).toHaveAttribute('aria-pressed', 'false')

    const speak = (socket: FakeWebSocket, turn: string, text: string, clip: string) => {
      socket.emit({ type: 'turn.started', turn_id: turn, details: {} })
      socket.emit({ type: 'llm.delta', turn_id: turn, details: { text } })
      socket.emit({ type: 'tts.audio.chunk', turn_id: turn, details: { mime_type: 'audio/mpeg', data: btoa(clip), chunk_index: 0 } })
      socket.emit({ type: 'turn.completed', turn_id: turn, details: { text } })
    }
    speak(FakeWebSocket.instances[0], 'turn-1', '这句不读出来。', 'one')
    expect(await said('这句不读出来。')).toBeVisible()
    expect(audio.played).toEqual([])
    fireEvent.contextMenu(await said('这句不读出来。'))
    fireEvent.click(screen.getByRole('menuitem', { name: '朗读' }))
    expect(audio.played).toEqual(['audio/mpeg:one'])

    // 这个选择记在浏览器里，下次打开还是关着。
    first.unmount()
    audio.played = []
    render(<CompanionApp />)
    expect(await screen.findByRole('button', { name: '打开自动朗读' })).toBeVisible()
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    speak(FakeWebSocket.instances[FakeWebSocket.instances.length - 1], 'turn-2', '这句也不读。', 'two')
    expect(await said('这句也不读。')).toBeVisible()
    expect(audio.played).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: '打开自动朗读' }))
    speak(FakeWebSocket.instances[FakeWebSocket.instances.length - 1], 'turn-3', '这句读出来。', 'three')
    expect(await said('这句读出来。')).toBeVisible()
    expect(audio.played).toEqual(['audio/mpeg:three'])
  })

  it('swaps the input bar for a call bar during a call and keeps what was said in the conversation', async () => {
    vi.stubEnv('NEXT_PUBLIC_CHARACTER_STYLE', '2d')
    const stopTrack = stubMicrophone()

    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '开始实时通话' }))

    // 通话中：底部的输入栏换成通话的一条，上面写着现在的状态。
    const bar = await screen.findByRole('group', { name: '语音通话' })
    expect(bar).toHaveTextContent('通话中，直接说话就可以')
    expect(screen.queryByRole('textbox', { name: '输入消息' })).not.toBeInTheDocument()

    // 通话一开始角色自己走到前面来。它可以缩成桌宠，把地方让给对话；点一下桌宠又放大回来。
    expect(screen.getByRole('region', { name: '小夏的形象' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '缩小' }))
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '放大小夏' }))
    expect(screen.getByRole('region', { name: '小夏的形象' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '放大小夏' })).not.toBeInTheDocument()
    // 通话时翻对话，角色也不缩。
    fireEvent.wheel(screen.getByRole('log'))
    expect(screen.getByRole('region', { name: '小夏的形象' })).toBeVisible()

    // 可以暂时关掉麦克风，不挂断。
    fireEvent.click(within(bar).getByRole('button', { name: '关闭麦克风' }))
    expect(bar).toHaveTextContent('麦克风已关')
    fireEvent.click(within(bar).getByRole('button', { name: '打开麦克风' }))
    expect(bar).toHaveTextContent('通话中，直接说话就可以')

    // 说的话照常进对话记录。
    const socket = FakeWebSocket.instances[0]
    socket.emit({ type: 'asr.partial', details: { text: '今天中午' } })
    expect(await said('今天中午')).toBeVisible()
    socket.emit({ type: 'asr.final', details: { text: '今天中午吃了面' } })
    await waitFor(() => expect(bar).toHaveTextContent('在想…'))
    socket.emit({ type: 'turn.started', turn_id: 'turn-v', details: { input_mode: 'audio' } })
    socket.emit({ type: 'llm.delta', turn_id: 'turn-v', details: { text: '听着就香。' } })
    socket.emit({ type: 'turn.completed', turn_id: 'turn-v', details: { text: '听着就香。' } })
    expect(await said('听着就香。')).toBeVisible()

    // 角色念到哪句，字幕就显示哪句。语音是一句一句到的，每句带着它的文字。
    socket.emit({ type: 'tts.audio', turn_id: 'turn-v', details: { mime_type: 'audio/mpeg', data: btoa('xiang'), text: '听着就香。', segment_index: 0 } })
    expect(audio.texts).toEqual(['听着就香。'])
    audio.playing = true; audio.current = '听着就香。'
    await waitFor(() => expect(document.querySelector('.figure-slot .caption')).toHaveTextContent('听着就香。'))
    audio.playing = false; audio.current = ''
    await waitFor(() => expect(document.querySelector('.figure-slot .caption')).toBeNull())

    fireEvent.click(screen.getByRole('button', { name: '结束实时通话' }))
    expect(await screen.findByRole('button', { name: '开始实时通话' })).toBeEnabled()
    expect(screen.queryByRole('group', { name: '语音通话' })).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '输入消息' })).toBeEnabled()
    expect(stopTrack).toHaveBeenCalled()
    // 挂断只是不再收音。连接还是原来那条，没有断开重连，接着打字不用等。
    expect(socket.sent.some((item) => item.type === 'stream.stop')).toBe(false)
    expect(FakeWebSocket.instances).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent('在线')
    fireEvent.change(screen.getByRole('textbox', { name: '输入消息' }), { target: { value: '挂了再打字' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await said('你好，我在线。')).toBeVisible()
    // 通话结束，角色缩回角落，还在页面上。
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '放大小夏' })).toBeInTheDocument()
    expect(chat().getByText('今天中午吃了面')).toBeVisible()
    expect(chat().getByText('听着就香。')).toBeVisible()
  })

  it('stops and says so when todays conversations are used up, instead of retrying', async () => {
    let attempts = 0
    const defaultFetch = globalThis.fetch
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/conversations') {
        attempts += 1
        return new Response(JSON.stringify({ error: '今天的对话次数已经用完了，明天再来吧', code: 10212 }), { status: 400 })
      }
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)

    expect(await screen.findByText(/今天聊得够多啦/)).toBeVisible()
    expect(screen.getByRole('textbox', { name: '输入消息' })).toBeDisabled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await new Promise((resolve) => setTimeout(resolve, 1300))
    expect(attempts).toBe(1)
  })

  it('stops when the runtime reports the daily limit in the middle of a session', async () => {
    let sessions = 0
    const defaultFetch = globalThis.fetch
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/conversations') sessions += 1
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())

    FakeWebSocket.instances[0].emit({
      type: 'error', sequence: 9, details: { code: 'daily_quota_exceeded', message: '今天的对话次数已经用完了，明天再来吧' },
    })

    expect(await screen.findByText(/今天聊得够多啦/)).toBeVisible()
    await new Promise((resolve) => setTimeout(resolve, 1300))
    expect(sessions).toBe(1)
  })

  it('always says the role is an AI and replaces a reply the server withdraws', async () => {
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    expect(screen.getByText(/是 AI，不是真人/)).toBeVisible()

    const socket = FakeWebSocket.instances[0]
    socket.emit({ type: 'turn.started', turn_id: 'turn-9', sequence: 20, details: {} })
    socket.emit({ type: 'llm.delta', turn_id: 'turn-9', sequence: 21, details: { text: '不该出现的内容' } })
    expect(await said('不该出现的内容')).toBeVisible()

    socket.emit({ type: 'reply.withdrawn', turn_id: 'turn-9', sequence: 22, details: { text: '刚才那段话不太合适，我收回。' } })

    expect(await said('刚才那段话不太合适，我收回。')).toBeVisible()
    expect(chat().queryByText('不该出现的内容')).not.toBeInTheDocument()
  })

  it('plays a reply sentence by sentence as the audio arrives and can replay the whole reply', async () => {
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    const socket = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    socket.emit({ type: 'turn.started', turn_id: 'turn-9', details: {} })
    socket.emit({ type: 'llm.delta', turn_id: 'turn-9', details: { text: '第一句。' } })
    // 第一句的语音在第二句的文字之前就到了。
    socket.emit({ type: 'tts.audio.chunk', turn_id: 'turn-9', details: { mime_type: 'audio/mpeg', data: btoa('one'), chunk_index: 0 } })
    expect(audio.played).toEqual(['audio/mpeg:one'])
    socket.emit({ type: 'llm.delta', turn_id: 'turn-9', details: { text: '第二句。' } })
    socket.emit({ type: 'tts.audio.chunk', turn_id: 'turn-9', details: { mime_type: 'audio/mpeg', data: btoa('two'), chunk_index: 1 } })
    socket.emit({ type: 'tts.audio.done', turn_id: 'turn-9', details: { chunk_count: 2 } })
    socket.emit({ type: 'turn.completed', turn_id: 'turn-9', details: { text: '第一句。第二句。' } })
    expect(await said('第一句。第二句。')).toBeVisible()
    expect(audio.played).toEqual(['audio/mpeg:one', 'audio/mpeg:two'])

    // 长按回复选“重新朗读”，从第一句开始按顺序再播一遍。
    audio.played = []
    fireEvent.contextMenu(await said('第一句。第二句。'))
    fireEvent.click(screen.getByRole('menuitem', { name: '朗读' }))
    expect(audio.stops).toBeGreaterThan(0)
    expect(audio.played).toEqual(['audio/mpeg:one', 'audio/mpeg:two'])
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('keeps a reply in one bubble when its first sentence of audio arrives before any text', async () => {
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    const socket = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    socket.emit({ type: 'turn.started', turn_id: 'turn-7', details: {} })
    socket.emit({ type: 'tts.audio.chunk', turn_id: 'turn-7', details: { mime_type: 'audio/mpeg', data: btoa('one'), chunk_index: 0 } })
    socket.emit({ type: 'llm.delta', turn_id: 'turn-7', details: { text: '好啊，快说吧。' } })
    socket.emit({ type: 'tts.audio.chunk', turn_id: 'turn-7', details: { mime_type: 'audio/mpeg', data: btoa('two'), chunk_index: 1 } })
    socket.emit({ type: 'turn.completed', turn_id: 'turn-7', details: { text: '好啊，快说吧。' } })
    const bubble = await said('好啊，快说吧。')
    expect(chat().getAllByRole('button', { name: /./ }).filter((item) => item.classList.contains('reply-bubble'))).toHaveLength(1)
    audio.played = []
    fireEvent.contextMenu(bubble)
    fireEvent.click(screen.getByRole('menuitem', { name: '朗读' }))
    expect(audio.played).toEqual(['audio/mpeg:one', 'audio/mpeg:two'])
  })

  it('does nothing on a tap and offers copy and read aloud on a long press', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    const socket = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    socket.emit({ type: 'llm.delta', turn_id: 'turn-3', details: { text: '今天也辛苦啦。' } })
    const bubble = await said('今天也辛苦啦。')

    // 点一下什么都不做，滚动时手指碰到气泡不会误触。
    fireEvent.click(bubble)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(writeText).not.toHaveBeenCalled()

    fireEvent.contextMenu(bubble)
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['复制', '朗读'])
    fireEvent.click(screen.getByRole('menuitem', { name: '复制' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('今天也辛苦啦。'))
    expect(await screen.findByText('已复制')).toBeVisible()
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('reads aloud a reply loaded from history by asking the server, even with reading aloud turned off', async () => {
    const defaultFetch = globalThis.fetch
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).startsWith('/api/history')) return new Response(JSON.stringify({ data: [
        { turn_id: 'old-1', text: '我回来了', reply: '欢迎回来。' },
      ] }), { status: 200 })
      return defaultFetch(input, init)
    }))
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    fireEvent.click(screen.getByRole('button', { name: '关闭自动朗读' }))
    const socket = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]

    fireEvent.contextMenu(await said('欢迎回来。'))
    fireEvent.click(screen.getByRole('menuitem', { name: '朗读' }))
    const request = socket.sent.find((item) => item.type === 'reply.read')
    expect(request).toMatchObject({ turn_id: 'old-1' })

    socket.emit({ type: 'tts.audio', turn_id: 'old-1', details: { mime_type: 'audio/mpeg', data: btoa('back'), purpose: 'read' } })
    socket.emit({ type: 'reply.read.done', turn_id: 'old-1', details: {} })
    expect(audio.played).toEqual(['audio/mpeg:back'])

    // 读过一次的语音记在回复上，再读就直接播，不再问服务端。
    audio.played = []
    fireEvent.contextMenu(await said('欢迎回来。'))
    fireEvent.click(screen.getByRole('menuitem', { name: '朗读' }))
    expect(audio.played).toEqual(['audio/mpeg:back'])
    expect(socket.sent.filter((item) => item.type === 'reply.read')).toHaveLength(1)
  })

  it('says so when a reply cannot be read aloud', async () => {
    const defaultFetch = globalThis.fetch
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).startsWith('/api/history')) return new Response(JSON.stringify({ data: [
        { turn_id: 'old-2', text: '嗨', reply: '嗨呀。' },
      ] }), { status: 200 })
      return defaultFetch(input, init)
    }))
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    const socket = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    fireEvent.contextMenu(await said('嗨呀。'))
    fireEvent.click(screen.getByRole('menuitem', { name: '朗读' }))
    socket.emit({ type: 'reply.read.failed', turn_id: 'old-2', details: { reason: 'tts_failed' } })
    expect(await screen.findByText('这条回复暂时没法朗读')).toBeVisible()
  })

  it('suggests a break after two hours of continuous use and starts counting again once dismissed', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      render(<CompanionApp />)
      await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
      const send = (text: string) => {
        fireEvent.change(screen.getByRole('textbox', { name: '输入消息' }), { target: { value: text } })
        fireEvent.click(screen.getByRole('button', { name: '发送' }))
      }

      send('开始聊')
      // 每十分钟说一句，一直聊了两个小时。
      for (let minute = 10; minute <= 120; minute += 10) {
        await vi.advanceTimersByTimeAsync(10 * 60 * 1000)
        send(`第 ${minute} 分钟`)
      }
      await vi.advanceTimersByTimeAsync(61 * 1000)

      expect(await screen.findByText(/已经连续聊了两个小时啦/)).toBeVisible()
      fireEvent.click(screen.getByRole('button', { name: '知道了' }))
      expect(screen.queryByText(/已经连续聊了两个小时啦/)).not.toBeInTheDocument()

      await vi.advanceTimersByTimeAsync(5 * 60 * 1000)
      expect(screen.queryByText(/已经连续聊了两个小时啦/)).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  }, SIMULATED_TIME_TIMEOUT_MS)

  it('asks a visitor without a session to sign in with their phone', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/session') {
        return new Response(JSON.stringify({ error: '未登录' }), { status: 401 })
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 })
    }))

    render(<CompanionApp />)

    expect(await screen.findByRole('tab', { name: '登录' })).toBeVisible()
    expect(screen.getByRole('textbox', { name: '手机号' })).toBeVisible()
    expect(screen.queryByRole('textbox', { name: '输入消息' })).not.toBeInTheDocument()
  })

  it('lets a new phone account pick its first role before chatting', async () => {
    let agents: unknown[] = []
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/session') return new Response(JSON.stringify({ data: { id: '9', sessionKind: 'app' } }), { status: 200 })
      if (url === '/api/agents') return new Response(JSON.stringify({ data: agents }), { status: 200 })
      if (url === '/api/role-templates') return new Response(JSON.stringify({ data: [{ id: 'tpl-1', name: '紫萱' }] }), { status: 200 })
      if (url === '/api/roles' && init?.method === 'POST') {
        agents = [{ id: 'agent-new', agentName: '紫萱', activeVersionNo: 1 }]
        return new Response(JSON.stringify({ data: { id: 'agent-new' } }), { status: 200 })
      }
      if (url.startsWith('/api/voices')) return new Response(JSON.stringify({ data: [] }), { status: 200 })
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<CompanionApp />)

    expect(await screen.findByRole('heading', { name: '选一个陪伴角色' })).toBeVisible()
    fireEvent.click(await screen.findByRole('button', { name: /紫萱/ }))

    expect(await screen.findByRole('heading', { name: '紫萱' })).toBeVisible()
    const created = fetchMock.mock.calls.find(([url]) => String(url) === '/api/roles')
    expect(JSON.parse(String(created?.[1]?.body))).toEqual({ templateId: 'tpl-1', name: '紫萱' })
  })

  it('lets a user who already has a role add another preset and switches to it', async () => {
    const defaultFetch = globalThis.fetch
    let created = false
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/session') return new Response(JSON.stringify({ data: { id: '7', username: '138****0001', kind: 'app' } }), { status: 200 })
      if (url === '/api/agents') return new Response(JSON.stringify({ data: [
        { id: 'agent-a', agentName: '紫萱', activeVersionNo: 1 },
        ...(created ? [{ id: 'agent-b', agentName: '阿澈', activeVersionNo: 1 }] : []),
      ] }), { status: 200 })
      if (url === '/api/role-templates') return new Response(JSON.stringify({ data: [
        { id: 'tpl-1', name: '紫萱', description: '温柔的倾听者' },
        { id: 'tpl-2', name: '阿澈', description: '冷静直接的朋友' },
      ] }), { status: 200 })
      if (url === '/api/roles' && init?.method === 'POST') { created = true; return new Response(JSON.stringify({ data: { id: 'agent-b' } }), { status: 200 }) }
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)
    expect(await screen.findByRole('button', { name: /^紫萱/ })).toHaveAttribute('aria-current', 'true')
    fireEvent.click(screen.getByRole('button', { name: '添加角色' }))

    // 已经有的角色不能重复添加，每个角色有一句介绍。
    expect(await screen.findByText('冷静直接的朋友')).toBeVisible()
    expect(screen.getByRole('button', { name: /紫萱.*已添加/ })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: /阿澈/ }))

    // 添加完回到对话，并且已经切到新角色。
    await waitFor(() => expect(screen.getByRole('button', { name: /^阿澈/ })).toHaveAttribute('aria-current', 'true'))
    expect(screen.getByRole('button', { name: /^紫萱/ })).not.toHaveAttribute('aria-current')
    expect(screen.getByText(/阿澈\s*是 AI，不是真人/)).toBeVisible()
  })

  it('says right under the chosen role when the plan has no room for another one', async () => {
    const defaultFetch = globalThis.fetch
    let posts = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/session') return new Response(JSON.stringify({ data: { id: '7', username: '138****0001', kind: 'app' } }), { status: 200 })
      if (url === '/api/agents') return new Response(JSON.stringify({ data: [{ id: 'agent-a', agentName: '紫萱', activeVersionNo: 1 }] }), { status: 200 })
      if (url === '/api/role-templates') return new Response(JSON.stringify({ data: [
        { id: 'tpl-1', name: '紫萱', description: '温柔的倾听者' },
        { id: 'tpl-2', name: '阿澈', description: '冷静直接的朋友' },
        { id: 'tpl-3', name: '小满', description: '开朗好奇的搭子' },
        { id: 'tpl-4', name: '枫糖', description: '戴耳机的小狐狸' },
      ] }), { status: 200 })
      if (url === '/api/roles' && init?.method === 'POST') {
        posts += 1
        return new Response(JSON.stringify({ error: '角色数量已达到当前套餐上限', code: 500 }), { status: 400 })
      }
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)
    fireEvent.click(await screen.findByRole('button', { name: '添加角色' }))
    fireEvent.click(await screen.findByRole('button', { name: /阿澈/ }))

    // 错误紧跟在点的那张卡后面，说明下一步怎么做；卡片可以再点。
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('删掉一个不常用的角色')
    expect(alert.previousElementSibling).toBe(screen.getByRole('button', { name: /阿澈/ }))
    expect(screen.getByRole('button', { name: /阿澈/ })).toBeEnabled()
    expect(screen.getAllByRole('alert')).toHaveLength(1)

    // 再连着点：不再问服务端，提示一直在，挪到刚点的卡下面，不会一闪一闪。
    for (let i = 0; i < 3; i += 1) fireEvent.click(screen.getByRole('button', { name: /阿澈/ }))
    fireEvent.click(screen.getByRole('button', { name: /小满/ }))
    expect(posts).toBe(1)
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(screen.getByRole('alert').previousElementSibling).toBe(screen.getByRole('button', { name: /小满/ }))

    // 顶上的返回回到对话。
    fireEvent.click(screen.getByRole('button', { name: '返回' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /小满/ })).toBeNull())
  })

  it('lets the user change a role and puts the new settings to use straight away', async () => {
    const defaultFetch = globalThis.fetch
    let name = '小夏'
    let saved: Record<string, unknown> | null = null
    let sessions = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/agents') return new Response(JSON.stringify({ data: [{ id: 'agent-a', agentName: name, activeVersionNo: 4, ttsModelId: 'tts-a', ttsVoiceId: 'voice-a' }] }), { status: 200 })
      if (url === '/api/conversations') sessions += 1
      if (url === '/api/roles/agent-a' && (!init?.method || init.method === 'GET')) return new Response(JSON.stringify({ data: {
        id: 'agent-a', name, userAddress: '', personality: '话多', systemPrompt: '你是小夏。', fromPreset: true, ttsModelId: 'tts-a', ttsVoiceId: 'voice-a',
      } }), { status: 200 })
      if (url === '/api/roles/agent-a' && init?.method === 'PUT') {
        saved = JSON.parse(String(init.body)) as Record<string, unknown>
        name = String(saved.name)
        return new Response(JSON.stringify({ data: { id: 'agent-a' } }), { status: 200 })
      }
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    expect(sessions).toBe(1)
    fireEvent.click(screen.getByRole('button', { name: '设置小夏' }))

    const editor = await screen.findByRole('main', { name: '角色设置' })
    const nameInput = await within(editor).findByRole('textbox', { name: '名字' })
    expect(nameInput).toHaveValue('小夏')
    // 声音在角色设置里选，选了会保存下来。
    expect(await within(editor).findByRole('button', { name: '声音：女声，点一下换声音' })).toBeInTheDocument()
    // 性格可以自己写，也可以点现成的词；再点一次去掉。
    fireEvent.click(within(editor).getByRole('button', { name: '温柔' }))
    expect(within(editor).getByRole('textbox', { name: '性格' })).toHaveValue('话多、温柔')
    fireEvent.click(within(editor).getByRole('button', { name: '话多' }))
    expect(within(editor).getByRole('textbox', { name: '性格' })).toHaveValue('温柔')
    // 形象：换成兔子、桃粉色。
    fireEvent.click(within(editor).getByRole('button', { name: /^形象：.*点一下换形象$/ }))
    fireEvent.click(within(editor).getByRole('radio', { name: '兔子' }))
    fireEvent.click(within(editor).getByRole('radio', { name: '桃粉' }))
    fireEvent.click(within(editor).getByRole('button', { name: '完成' }))
    fireEvent.change(nameInput, { target: { value: '  小秋 ' } })
    fireEvent.change(within(editor).getByRole('textbox', { name: '它怎么称呼你' }), { target: { value: '阿远' } })
    fireEvent.click(within(editor).getByRole('button', { name: '保存' }))

    // 保存后名字换了，对话换了一个新会话来用上新的设置，选的形象记在这台设备上。
    expect(await screen.findByRole('heading', { name: '小秋' })).toBeVisible()
    expect(saved).toEqual({ name: '小秋', userAddress: '阿远', personality: '温柔', systemPrompt: '你是小夏。', ttsVoiceId: 'voice-a' })
    await waitFor(() => expect(sessions).toBe(2))
    expect(screen.queryByRole('main', { name: '角色设置' })).not.toBeInTheDocument()
    expect(JSON.parse(window.localStorage.getItem('companion.look.agent-a') || 'null')).toMatchObject({ kind: 'rabbit', color: 'peach' })
  })

  it('shows what a role remembers and lets the user forget an item, add one, or forget everything', async () => {
    const defaultFetch = globalThis.fetch
    let memories = [
      { id: '11', content: '用户养了一只叫年糕的猫', source: 'conversation' },
      { id: '12', content: '用户下个月有一场面试', source: 'conversation' },
    ]
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/roles/agent-a') return new Response(JSON.stringify({ data: {
        id: 'agent-a', name: '小夏', userAddress: '', personality: '', systemPrompt: '', fromPreset: true, ttsModelId: null, ttsVoiceId: '',
      } }), { status: 200 })
      if (url.startsWith('/api/memories')) {
        const method = init?.method || 'GET'
        if (method === 'POST') {
          const body = JSON.parse(String(init?.body)) as { agentId: string; content: string }
          calls.push(`add ${body.agentId} ${body.content}`)
          memories = [...memories, { id: '13', content: body.content, source: 'user' }]
        } else if (method === 'DELETE') {
          const id = new URL(url, 'http://local').searchParams.get('id')
          calls.push(id ? `forget ${id}` : 'forget all')
          memories = id ? memories.filter((item) => item.id !== id) : []
        }
        return new Response(JSON.stringify({ data: method === 'GET' ? memories : {} }), { status: 200 })
      }
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    fireEvent.click(screen.getByRole('button', { name: '设置小夏' }))
    const list = await screen.findByRole('list', { name: '它记得的事' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)

    // 忘掉一条：立刻生效，不用点保存。
    fireEvent.click(within(list).getByRole('button', { name: '忘掉：用户下个月有一场面试' }))
    await waitFor(() => expect(screen.queryByText('用户下个月有一场面试')).not.toBeInTheDocument())

    // 自己写一条让它记住。
    const note = screen.getByRole('textbox', { name: '写一件事让它记住' })
    fireEvent.change(note, { target: { value: ' 我不吃香菜 ' } })
    fireEvent.click(screen.getByRole('button', { name: '记住' }))
    expect(await screen.findByText('我不吃香菜')).toBeVisible()
    expect(note).toHaveValue('')

    fireEvent.click(screen.getByRole('button', { name: '全部忘掉' }))
    expect(await screen.findByText(/它还没有记下什么/)).toBeVisible()
    expect(calls).toEqual(['forget 12', 'add agent-a 我不吃香菜', 'forget all'])
  })

  it('starts a blank role, opens its settings, and can delete a role after asking once more', async () => {
    const defaultFetch = globalThis.fetch
    let agents = [{ id: 'agent-a', agentName: '紫萱', activeVersionNo: 1 }]
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/session') return new Response(JSON.stringify({ data: { id: '7', sessionKind: 'app' } }), { status: 200 })
      if (url === '/api/agents') return new Response(JSON.stringify({ data: agents }), { status: 200 })
      if (url === '/api/role-templates') return new Response(JSON.stringify({ data: [{ id: 'tpl-1', name: '紫萱', description: '温柔的倾听者' }] }), { status: 200 })
      if (url === '/api/roles' && init?.method === 'POST') {
        calls.push(`create ${String(init.body)}`)
        agents = [...agents, { id: 'agent-n', agentName: '新朋友', activeVersionNo: 1 }]
        return new Response(JSON.stringify({ data: { id: 'agent-n' } }), { status: 200 })
      }
      if (url === '/api/roles/agent-n' && init?.method === 'DELETE') {
        calls.push('delete agent-n')
        agents = agents.filter((agent) => agent.id !== 'agent-n')
        return new Response(JSON.stringify({ data: { id: 'agent-n' } }), { status: 200 })
      }
      if (url === '/api/roles/agent-n') return new Response(JSON.stringify({ data: {
        id: 'agent-n', name: '新朋友', userAddress: '', personality: '', systemPrompt: '你是用户的陪伴角色。', fromPreset: true, ttsModelId: null, ttsVoiceId: '',
      } }), { status: 200 })
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    fireEvent.click(screen.getByRole('button', { name: '添加角色' }))
    fireEvent.click(await screen.findByRole('button', { name: /从空白开始/ }))

    // 空白角色建好后直接打开它的设置，并且已经切到它。
    const editor = await screen.findByRole('main', { name: '角色设置' })
    expect(within(editor).getByRole('heading', { name: '新角色' })).toBeVisible()
    expect(calls).toEqual(['create {"blank":true,"name":"新朋友"}'])
    expect(await within(editor).findByRole('textbox', { name: '性格' })).toHaveValue('')

    // 删除要再确认一次。删掉后回到剩下的角色。
    fireEvent.click(within(editor).getByRole('button', { name: '删除这个角色' }))
    expect(calls).toHaveLength(1)
    expect(within(editor).getByText(/对话记录会一起删掉/)).toBeVisible()
    fireEvent.click(within(editor).getByRole('button', { name: '确认删除' }))
    expect(await screen.findByRole('heading', { name: '紫萱' })).toBeVisible()
    expect(calls).toEqual(['create {"blank":true,"name":"新朋友"}', 'delete agent-n'])
    expect(screen.queryByRole('main', { name: '角色设置' })).not.toBeInTheDocument()
  })

  it('signs in with a handover code from a listed console address and ignores other senders', async () => {
    let signedIn = false
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/session') return new Response(JSON.stringify(signedIn ? { data: { id: '7', sessionKind: 'console' } } : { error: '未登录' }), { status: signedIn ? 200 : 401 })
      if (url === '/api/auth/exchange') { signedIn = true; return new Response(JSON.stringify({ data: { ok: true } }), { status: 200 }) }
      if (url === '/api/agents') return new Response(JSON.stringify({ data: [{ id: 'agent-a', agentName: '小夏', activeVersionNo: 4 }] }), { status: 200 })
      return new Response(JSON.stringify({ data: [] }), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<CompanionApp />)
    expect(await screen.findByRole('tab', { name: '登录' })).toBeVisible()
    const exchanges = () => fetchMock.mock.calls.filter(([url]) => String(url) === '/api/auth/exchange')

    // 别的网站发来的不理。
    window.dispatchEvent(new MessageEvent('message', { origin: 'https://evil.example', data: { type: 'companion.bootstrap', code: 'stolen' } }))
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(exchanges()).toHaveLength(0)

    // 默认同时认 127.0.0.1 和 localhost 打开的管理台。
    window.dispatchEvent(new MessageEvent('message', { origin: 'http://localhost:8001', data: { type: 'companion.bootstrap', code: 'one-time' } }))
    await waitFor(() => expect(exchanges()).toHaveLength(1))
    expect(JSON.parse(String(exchanges()[0][1]?.body))).toEqual({ code: 'one-time' })
  })

  it('does not offer roles that have no published version and asks for a role instead', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/session') return new Response(JSON.stringify({ data: { id: '7', sessionKind: 'console' } }), { status: 200 })
      if (url === '/api/agents') return new Response(JSON.stringify({ data: [{ id: 'draft', agentName: '草稿角色', activeVersionNo: null }] }), { status: 200 })
      return new Response(JSON.stringify({ data: [] }), { status: 200 })
    }))

    render(<CompanionApp />)

    // 从管理台进来、还没有发布过的角色的账号，也和新用户一样先选一个角色，而不是停在一个空页面上。
    expect(await screen.findByRole('heading', { name: '选一个陪伴角色' })).toBeVisible()
    expect(screen.queryByText('草稿角色')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: '输入消息' })).not.toBeInTheDocument()
  })

  it('lets someone who came from the console manage roles, but leaves signing out and deleting the account to the console', async () => {
    const defaultFetch = globalThis.fetch
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/session') return new Response(JSON.stringify({ data: { id: '7', sessionKind: 'console' } }), { status: 200 })
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    expect(screen.getByRole('button', { name: '添加角色' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '设置小夏' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '个人中心' }))
    expect(await screen.findByRole('heading', { name: '个人中心' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '退出登录' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /注销账号/ })).not.toBeInTheDocument()
  })
})
