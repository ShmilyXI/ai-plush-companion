import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CompanionApp from './CompanionApp'

// 测试环境里没有声卡。这里只记下页面要求播放了什么、按什么顺序。
const audio = vi.hoisted(() => ({ played: [] as string[], stops: 0 }))
vi.mock('../lib/playback', () => ({
  createPlayback: () => ({
    unlock() {},
    play(bytes: Uint8Array, mimeType: string) { audio.played.push(`${mimeType}:${new TextDecoder().decode(bytes)}`) },
    stop() { audio.stops += 1 },
    isPlaying: () => false,
    playedMs: () => 0,
    dispose() {},
  }),
}))

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
  beforeEach(() => {
    FakeWebSocket.instances = []
    audio.played = []; audio.stops = 0
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
    // 选好角色后自动连接，不需要手动点“连接”。
    await waitFor(() => expect(screen.getByRole('button', { name: '开始实时通话' })).toBeEnabled())
    fireEvent.change(screen.getByRole('textbox', { name: '输入消息' }), { target: { value: '你好' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await screen.findByText('你好，我在线。')).toBeVisible()
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

    expect(await screen.findByText('我的猫叫年糕')).toBeVisible()
    expect(screen.getByText('年糕这名字真可爱')).toBeVisible()
    await waitFor(() => expect(screen.getByText('已连接')).toBeVisible())
    expect(sessions).toBe(1)

    // 连接意外断开：不需要用户做任何事，稍后自动连上，期间仍然可以输入。
    FakeWebSocket.instances[0].emitClose()
    await waitFor(() => expect(screen.getByText('正在重新连接')).toBeVisible())
    expect(screen.getByRole('textbox', { name: '输入消息' })).toBeEnabled()
    fireEvent.change(screen.getByRole('textbox', { name: '输入消息' }), { target: { value: '你还在吗' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await screen.findByText('你还在吗')).toBeVisible()

    await waitFor(() => expect(sessions).toBe(2), { timeout: 4000 })
    // 断线期间发的那句话，连上之后自动补发。
    await waitFor(() => expect(FakeWebSocket.instances[1].sent.some(
      (payload) => payload.type === 'turn.text' && payload.text === '你还在吗')).toBe(true))
    expect(await screen.findByText('你好，我在线。')).toBeVisible()
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
    expect(await screen.findByText('面试怎么样了？')).toBeVisible()
    expect(document.querySelectorAll('article.message.user')).toHaveLength(1)
    await waitFor(() => expect(screen.getByText('已连接')).toBeVisible())
    const openings = (socket: FakeWebSocket) => socket.sent.filter((payload) => payload.type === 'conversation.opening')
    expect(openings(FakeWebSocket.instances[0])).toHaveLength(1)

    // 角色先开口：像普通回复一样显示出来。
    FakeWebSocket.instances[0].emit({ type: 'turn.started', turn_id: 'turn-o', details: { input_mode: 'opening' } })
    FakeWebSocket.instances[0].emit({ type: 'llm.delta', turn_id: 'turn-o', details: { text: '今天过得怎么样？' } })
    FakeWebSocket.instances[0].emit({ type: 'turn.completed', turn_id: 'turn-o', details: { text: '今天过得怎么样？', opening: true } })
    expect(await screen.findByText('今天过得怎么样？')).toBeVisible()

    // 断线重连是同一次访问，不再请求。
    FakeWebSocket.instances[0].emitClose()
    await waitFor(() => expect(FakeWebSocket.instances.length).toBe(2), { timeout: 4000 })
    await waitFor(() => expect(screen.getByText('已连接')).toBeVisible())
    expect(openings(FakeWebSocket.instances[1])).toHaveLength(0)
  })

  it('takes back an opening the server withdraws without leaving an empty bubble', async () => {
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('已连接')).toBeVisible())
    const socket = FakeWebSocket.instances[0]
    socket.emit({ type: 'turn.started', turn_id: 'turn-o', details: { input_mode: 'opening' } })
    socket.emit({ type: 'llm.delta', turn_id: 'turn-o', details: { text: '不该出现的开场白' } })
    expect(await screen.findByText('不该出现的开场白')).toBeVisible()

    socket.emit({ type: 'reply.withdrawn', turn_id: 'turn-o', details: { text: '' } })
    socket.emit({ type: 'turn.cancelled', turn_id: 'turn-o', details: { reason: 'opening_failed' } })

    await waitFor(() => expect(screen.queryByText('不该出现的开场白')).not.toBeInTheDocument())
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
    await waitFor(() => expect(screen.getByText('已连接')).toBeVisible())
    const first = FakeWebSocket.instances[0]
    let closed = false
    first.addEventListener('close', () => { closed = true })

    first.emit({ type: 'session.expiring', sequence: 9, details: { expires_at: 0 } })

    await waitFor(() => expect(sessions).toBe(2))
    await waitFor(() => expect(closed).toBe(true))
    // 换会话对用户不可见：没有报错，状态仍然是已连接，可以直接继续说话。
    await waitFor(() => expect(screen.getByText('已连接')).toBeVisible())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '输入消息' }), { target: { value: '继续聊' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(FakeWebSocket.instances[1].sent.some(
      (payload) => payload.type === 'turn.text' && payload.text === '继续聊')).toBe(true))
  })

  it('stops reconnecting when the user disconnects and resumes when asked', async () => {
    let sessions = 0
    const defaultFetch = globalThis.fetch
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/conversations') sessions += 1
      return defaultFetch(input, init)
    }))

    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('已连接')).toBeVisible())

    fireEvent.click(screen.getByRole('button', { name: '断开' }))
    expect(await screen.findByText('未连接')).toBeVisible()
    expect(screen.getByRole('textbox', { name: '输入消息' })).toBeDisabled()
    await new Promise((resolve) => setTimeout(resolve, 1300))
    expect(sessions).toBe(1)

    fireEvent.click(screen.getByRole('button', { name: '连接' }))
    await waitFor(() => expect(sessions).toBe(2))
    await waitFor(() => expect(screen.getByText('已连接')).toBeVisible())
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
    await waitFor(() => expect(screen.getByText('已连接')).toBeVisible())

    FakeWebSocket.instances[0].emit({
      type: 'error', sequence: 9, details: { code: 'daily_quota_exceeded', message: '今天的对话次数已经用完了，明天再来吧' },
    })

    expect(await screen.findByText(/今天聊得够多啦/)).toBeVisible()
    await new Promise((resolve) => setTimeout(resolve, 1300))
    expect(sessions).toBe(1)
  })

  it('always says the role is an AI and replaces a reply the server withdraws', async () => {
    render(<CompanionApp />)
    await waitFor(() => expect(screen.getByText('已连接')).toBeVisible())
    expect(screen.getByText(/是 AI，不是真人/)).toBeVisible()

    const socket = FakeWebSocket.instances[0]
    socket.emit({ type: 'turn.started', turn_id: 'turn-9', sequence: 20, details: {} })
    socket.emit({ type: 'llm.delta', turn_id: 'turn-9', sequence: 21, details: { text: '不该出现的内容' } })
    expect(await screen.findByText('不该出现的内容')).toBeVisible()

    socket.emit({ type: 'reply.withdrawn', turn_id: 'turn-9', sequence: 22, details: { text: '刚才那段话不太合适，我收回。' } })

    expect(await screen.findByText('刚才那段话不太合适，我收回。')).toBeVisible()
    expect(screen.queryByText('不该出现的内容')).not.toBeInTheDocument()
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
    expect(await screen.findByText('第一句。第二句。')).toBeVisible()
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
      await waitFor(() => expect(screen.getByText('已连接')).toBeVisible())
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

    expect(await screen.findByRole('option', { name: '紫萱' })).toBeVisible()
    const created = fetchMock.mock.calls.find(([url]) => String(url) === '/api/roles')
    expect(JSON.parse(String(created?.[1]?.body))).toEqual({ templateId: 'tpl-1', name: '紫萱' })
  })

  it('does not offer roles that have no published version', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/session') return new Response(JSON.stringify({ data: { id: '7', sessionKind: 'console' } }), { status: 200 })
      if (url === '/api/agents') return new Response(JSON.stringify({ data: [{ id: 'draft', agentName: '草稿角色', activeVersionNo: null }] }), { status: 200 })
      return new Response(JSON.stringify({ data: [] }), { status: 200 })
    }))

    render(<CompanionApp />)

    expect(await screen.findByRole('option', { name: '暂无已发布角色' })).toBeVisible()
    expect(screen.queryByRole('option', { name: '草稿角色' })).not.toBeInTheDocument()
  })
})
