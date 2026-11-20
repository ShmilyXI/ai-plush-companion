import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CompanionApp from './CompanionApp'

// 测试环境里没有声卡。这里只记下页面要求播放了什么、按什么顺序。
const audio = vi.hoisted(() => ({ played: [] as string[], stops: 0 }))
vi.mock('../lib/playback', () => ({
  createPlayback: () => ({
    unlock() {},
    play(bytes: Uint8Array, mimeType: string) { audio.played.push(`${mimeType}:${new TextDecoder().decode(bytes)}`) },
    stop() { audio.stops += 1 },
    isPlaying: () => false,
    level: () => 0,
    playedMs: () => 0,
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
  vi.stubGlobal('AudioContext', class {
    sampleRate = 16_000
    destination = {}
    createMediaStreamSource() { return node() }
    createScriptProcessor() { return node() }
    createGain() { return node() }
    async close() {}
  })
  return stopTrack
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
  send(value: unknown) {
    if (typeof value !== 'string') return
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

describe('CompanionApp', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  beforeEach(() => {
    FakeWebSocket.instances = []
    audio.played = []; audio.stops = 0
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

  it('opens the role list with a swipe to the right and closes it with a swipe to the left', async () => {
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('在线')).toBeVisible())
    const menu = screen.getByRole('button', { name: '切换角色' })
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

  it('shows the role only during a call, lets the shrunken role be dragged, and remembers that it was shrunk', async () => {
    stubMicrophone()
    const first = render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    // 没在通话时页面上没有角色形象，也没有放大缩小它的按钮。
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /角色形象/ })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '开始实时通话' }))
    expect(await screen.findByRole('region', { name: '小夏的形象' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '收起角色形象' }))
    const pet = screen.getByRole('button', { name: '放大小夏' })

    // 拖动：按下、移动一段、松开。松开时浏览器还会补一次点击，那一次不能把它放大。
    fireEvent.pointerDown(pet, { pointerId: 1, clientX: 300, clientY: 400 })
    fireEvent.pointerMove(pet, { pointerId: 1, clientX: 180, clientY: 320 })
    fireEvent.pointerUp(pet, { pointerId: 1, clientX: 180, clientY: 320 })
    fireEvent.click(pet)
    expect(screen.getByRole('button', { name: '放大小夏' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()

    // 下次通话它还是桌宠。
    first.unmount()
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '开始实时通话' }))
    fireEvent.click(await screen.findByRole('button', { name: '放大小夏' }))
    expect(screen.getByRole('region', { name: '小夏的形象' })).toBeVisible()
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
    fireEvent.click(screen.getByRole('button', { name: '重听' }))
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
    const stopTrack = stubMicrophone()

    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '开始实时通话' }))

    // 通话中：底部的输入栏换成通话的一条，上面写着现在的状态。
    const bar = await screen.findByRole('group', { name: '语音通话' })
    expect(bar).toHaveTextContent('通话中，直接说话就可以')
    expect(screen.queryByRole('textbox', { name: '输入消息' })).not.toBeInTheDocument()

    // 角色只在通话时出现。它可以缩成桌宠，把地方让给对话；点一下桌宠又放大回来。
    expect(screen.getByRole('region', { name: '小夏的形象' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '收起角色形象' }))
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '放大小夏' }))
    expect(screen.getByRole('region', { name: '小夏的形象' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '放大小夏' })).not.toBeInTheDocument()

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
    // 通话结束，角色也跟着离开，放大缩小的按钮一起消失。
    expect(screen.queryByRole('region', { name: '小夏的形象' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '收起角色形象' })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('button', { name: '放大小夏' })).not.toBeInTheDocument())
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

    // 一条回复只有一个“重听”，点了从第一句开始按顺序再播一遍。
    const replay = screen.getAllByRole('button', { name: '重听' })
    expect(replay).toHaveLength(1)
    audio.played = []
    fireEvent.click(replay[0])
    expect(audio.stops).toBeGreaterThan(0)
    expect(audio.played).toEqual(['audio/mpeg:one', 'audio/mpeg:two'])
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
  })

  it('asks a visitor without a session to sign in with their phone', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/session') {
        return new Response(JSON.stringify({ error: '未登录' }), { status: 401 })
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 })
    }))

    render(<CompanionApp />)

    expect(await screen.findByRole('heading', { name: '登录' })).toBeVisible()
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
    expect(await within(editor).findByRole('option', { name: '女声' })).toBeInTheDocument()
    // 性格可以自己写，也可以点现成的词；再点一次去掉。
    fireEvent.click(within(editor).getByRole('button', { name: '温柔' }))
    expect(within(editor).getByRole('textbox', { name: '性格' })).toHaveValue('话多、温柔')
    fireEvent.click(within(editor).getByRole('button', { name: '话多' }))
    expect(within(editor).getByRole('textbox', { name: '性格' })).toHaveValue('温柔')
    // 形象：换成兔子、桃粉色。
    fireEvent.click(within(editor).getByRole('radio', { name: '兔子' }))
    fireEvent.click(within(editor).getByRole('radio', { name: '桃粉' }))
    fireEvent.change(nameInput, { target: { value: '  小秋 ' } })
    fireEvent.change(within(editor).getByRole('textbox', { name: '它怎么称呼你' }), { target: { value: '阿远' } })
    fireEvent.click(within(editor).getByRole('button', { name: '保存' }))

    // 保存后名字换了，对话换了一个新会话来用上新的设置，选的形象记在这台设备上。
    expect(await screen.findByRole('heading', { name: '小秋' })).toBeVisible()
    expect(saved).toEqual({ name: '小秋', userAddress: '阿远', personality: '温柔', systemPrompt: '你是小夏。', ttsVoiceId: 'voice-a' })
    await waitFor(() => expect(sessions).toBe(2))
    expect(screen.queryByRole('main', { name: '角色设置' })).not.toBeInTheDocument()
    expect(JSON.parse(window.localStorage.getItem('companion.look.agent-a') || 'null')).toEqual({ kind: 'rabbit', color: 'peach' })
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

  it('does not offer roles that have no published version', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/session') return new Response(JSON.stringify({ data: { id: '7', sessionKind: 'console' } }), { status: 200 })
      if (url === '/api/agents') return new Response(JSON.stringify({ data: [{ id: 'draft', agentName: '草稿角色', activeVersionNo: null }] }), { status: 200 })
      return new Response(JSON.stringify({ data: [] }), { status: 200 })
    }))

    render(<CompanionApp />)

    expect(await screen.findByRole('heading', { name: '暂无已发布角色' })).toBeVisible()
    expect(screen.queryByText('草稿角色')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: '输入消息' })).not.toBeInTheDocument()
  })
})
