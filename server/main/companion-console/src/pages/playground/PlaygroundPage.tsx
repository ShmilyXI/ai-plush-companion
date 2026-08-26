import { AudioOutlined, DeleteOutlined, ReloadOutlined, SendOutlined, SettingOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Empty, Input, List, Select, Space, Tag, Typography, message } from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import { listProfileModelOptions, listProfiles, type CompanionProfile, type ProfileModelOption } from '../../api/profiles'
import {
  connectPlaygroundConversation,
  createPlaygroundConversation,
  type PlaygroundConversationClient,
  type PlaygroundConversationEvent,
  type PlaygroundConversationSession,
} from '../../api/playground'
import { loadPlaygroundStore, removeSession, upsertSession } from './playgroundStorage'
import type { PlaygroundEvent, PlaygroundMessage, PlaygroundSession, VirtualDeviceState } from './playgroundTypes'

const defaultDevice: VirtualDeviceState = {
  width: 240, height: 240, depth: 8, orientation: 'square',
  screen: true, camera: false, microphone: true, activitySensor: false,
}
const modelTypes = ['LLM', 'ASR', 'TTS', 'VAD', 'VLLM', 'Memory']

function resamplePcm(input: Float32Array, inputRate: number, outputRate = 16_000) {
  if (inputRate === outputRate) return input
  const ratio = inputRate / outputRate
  const output = new Float32Array(Math.round(input.length / ratio))
  for (let index = 0; index < output.length; index += 1) {
    const start = Math.floor(index * ratio)
    const end = Math.min(input.length, Math.floor((index + 1) * ratio))
    let sum = 0
    for (let source = start; source < end; source += 1) sum += input[source]
    output[index] = sum / Math.max(1, end - start)
  }
  return output
}

function encodePcm(input: Float32Array) {
  const pcm = new Uint8Array(input.length * 2)
  const view = new DataView(pcm.buffer)
  for (let index = 0; index < input.length; index += 1) {
    view.setInt16(index * 2, Math.max(-1, Math.min(1, input[index])) * 0x7fff, true)
  }
  return pcm
}

function newLocalSession(
  profile: CompanionProfile,
  runtime: PlaygroundConversationSession,
  models: Record<string, string>,
): PlaygroundSession {
  return {
    id: crypto.randomUUID(),
    title: `${profile.name} · 操练会话`,
    createdAt: new Date().toISOString(),
    playgroundSessionId: runtime.conversationId,
    agentVersion: runtime.agentVersion,
    runtimeCursor: 0,
    snapshot: {
      profileId: profile.id,
      profileName: profile.name,
      models,
      ttsVoiceId: profile.ttsVoiceId,
      skills: profile.skills?.filter((skill) => skill.enabled).map((skill) => skill.skillId) ?? [],
      rolePrompt: profile.personality,
      systemPrompt: profile.systemPrompt,
      virtualDevice: defaultDevice,
    },
    messages: [], events: [], screenState: {}, memories: [],
  }
}

function eventCapability(type: string) {
  if (type.startsWith('asr.')) return 'asr'
  if (type.startsWith('llm.')) return 'llm'
  if (type.startsWith('tts.')) return 'tts'
  if (type.startsWith('tool.')) return 'tool'
  return 'runtime'
}

function eventStatus(type: string): PlaygroundEvent['status'] {
  if (type === 'error' || type.endsWith('.failed')) return 'failed'
  if (type.endsWith('.started') || type.endsWith('.delta') || type.endsWith('.partial')) return 'started'
  return 'completed'
}

function eventText(event: PlaygroundConversationEvent) {
  const text = event.details.text
  if (typeof text === 'string') return text
  const message = event.details.message
  return typeof message === 'string' ? message : event.type
}

function toTimelineEvent(event: PlaygroundConversationEvent, extraDetails?: Record<string, unknown>): PlaygroundEvent {
  const text = eventText(event)
  return {
    sessionId: event.conversation_id,
    sequence: event.sequence,
    capability: eventCapability(event.type),
    stage: event.type,
    status: eventStatus(event.type),
    startedAt: event.occurred_at,
    finishedAt: eventStatus(event.type) === 'started' ? null : event.occurred_at,
    durationMs: null,
    inputSummary: '',
    outputSummary: text,
    error: eventStatus(event.type) === 'failed' ? text : null,
    details: { ...event.details, ...extraDetails },
  }
}

function audioDataUrl(details: Record<string, unknown>) {
  return typeof details.data === 'string'
    ? `data:${typeof details.mime_type === 'string' ? details.mime_type : 'audio/opus'};base64,${details.data}`
    : null
}

export function PlaygroundPage() {
  const initialStore = useMemo(() => loadPlaygroundStore(), [])
  const [profiles, setProfiles] = useState<CompanionProfile[]>([])
  const [sessions, setSessions] = useState<PlaygroundSession[]>(initialStore.sessions)
  const [active, setActive] = useState<PlaygroundSession | null>(() =>
    initialStore.sessions.find((item) => item.id === initialStore.activeSessionId) ?? null)
  const [profileId, setProfileId] = useState('')
  const [modelOptions, setModelOptions] = useState<ProfileModelOption[]>([])
  const [selectedModels, setSelectedModels] = useState<Record<string, string>>({})
  const [draft, setDraft] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [recording, setRecording] = useState(false)
  const [connected, setConnected] = useState(false)
  const [connectionLabel, setConnectionLabel] = useState(active ? '历史记录' : '未连接')
  const [error, setError] = useState('')
  const [messageApi, messageContext] = message.useMessage()
  const activeRef = useRef<PlaygroundSession | null>(active)
  const clientRef = useRef<PlaygroundConversationClient | null>(null)
  const recorderRef = useRef<MediaRecorder | null>(null)
  const turnRequests = useRef(new Map<string, string>())

  useEffect(() => {
    void listProfiles()
      .then((items) => {
        setProfiles(items)
        setProfileId(activeRef.current?.snapshot.profileId ?? items[0]?.id ?? '')
      })
      .catch((reason) => setError(reason instanceof Error ? reason.message : '角色列表加载失败'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    if (!profileId) { setModelOptions([]); return }
    const controller = new AbortController()
    void listProfileModelOptions(profileId, { signal: controller.signal })
      .then(setModelOptions)
      .catch((reason) => {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '模型列表加载失败')
      })
    return () => controller.abort()
  }, [profileId])

  const profile = useMemo(() => profiles.find((item) => item.id === profileId) ?? null, [profileId, profiles])
  const modelOptionsByType = useMemo(() => Object.fromEntries(
    modelTypes.map((type) => [type, modelOptions.filter((item) => item.modelType === type)]),
  ), [modelOptions])

  useEffect(() => {
    if (!profile || activeRef.current?.snapshot.profileId === profile.id) return
    setSelectedModels(Object.fromEntries(
      (profile.effectiveModels ?? []).filter((model) => model.resourceId).map((model) => [model.modelType, model.resourceId!]),
    ))
  }, [profile])

  useEffect(() => () => clientRef.current?.close(), [])

  function persist(next: PlaygroundSession) {
    activeRef.current = next
    setActive(next)
    setSessions((current) => [next, ...current.filter((item) => item.id !== next.id)])
    upsertSession(next)
  }

  function updateActive(transform: (session: PlaygroundSession) => PlaygroundSession) {
    const current = activeRef.current
    if (!current) return
    persist(transform(current))
  }

  function handleRuntimeEvent(event: PlaygroundConversationEvent) {
    if (event.conversation_id && event.conversation_id !== activeRef.current?.playgroundSessionId) return
    if (event.type === 'session.ready') {
      setConnected(true)
      setConnectionLabel('已连接')
      return
    }
    if (event.type === 'turn.started' && event.turn_id) {
      const requestId = typeof event.details.request_id === 'string' ? event.details.request_id : ''
      if (requestId) turnRequests.current.set(event.turn_id, requestId)
      return
    }
    if (event.type === 'llm.delta' && event.turn_id) {
      updateActive((session) => {
        const id = `assistant-${event.turn_id}`
        const existing = session.messages.find((item) => item.id === id)
        const text = `${existing?.text ?? ''}${eventText(event)}`
        const messageItem: PlaygroundMessage = {
          id, role: 'assistant', text, turnId: event.turn_id ?? undefined,
          createdAt: existing?.createdAt ?? new Date(event.occurred_at).toISOString(), capability: 'llm',
        }
        return {
          ...session,
          messages: existing ? session.messages.map((item) => item.id === id ? messageItem : item) : [...session.messages, messageItem],
        }
      })
      return
    }
    if (event.type === 'asr.final') {
      const requestId = event.turn_id ? turnRequests.current.get(event.turn_id) : null
      updateActive((session) => ({
        ...session,
        messages: session.messages.map((item) => item.requestId === requestId ? { ...item, text: eventText(event), capability: 'asr' } : item),
        events: [...session.events, toTimelineEvent(event, { transcript: eventText(event) })],
      }))
      return
    }
    if ((event.type === 'tts.audio' || event.type === 'tts.audio.chunk') && event.turn_id) {
      const url = audioDataUrl(event.details)
      updateActive((session) => ({
        ...session,
        events: [...session.events, toTimelineEvent(event, url ? { audioDataUrl: url } : undefined)],
      }))
      return
    }
    if (event.type === 'turn.completed') {
      setBusy(false)
      updateActive((session) => {
        if (!event.turn_id) return session
        const id = `assistant-${event.turn_id}`
        const finalText = eventText(event)
        const existing = session.messages.find((item) => item.id === id)
        if (existing) {
          return { ...session, messages: session.messages.map((item) => item.id === id ? { ...item, text: finalText || item.text } : item) }
        }
        return { ...session, messages: [...session.messages, {
          id, role: 'assistant', text: finalText, turnId: event.turn_id, capability: 'llm',
          createdAt: new Date(event.occurred_at).toISOString(),
        }] }
      })
      return
    }
    if (event.type === 'error' || event.type.endsWith('.failed')) {
      setBusy(false)
      const text = eventText(event)
      updateActive((session) => ({ ...session, events: [...session.events, toTimelineEvent(event)] }))
      messageApi.error(text)
      return
    }
    if (event.type === 'session.expired') {
      setConnected(false)
      setConnectionLabel('会话已过期')
      clientRef.current = null
      return
    }
    if (event.type.startsWith('tool.')) {
      updateActive((session) => ({ ...session, events: [...session.events, toTimelineEvent(event)] }))
    }
  }

  function attachRuntime(runtime: PlaygroundConversationSession) {
    clientRef.current?.close()
    setConnected(false)
    setConnectionLabel('连接中')
    const client = connectPlaygroundConversation(runtime, {
      onEvent: handleRuntimeEvent,
      onOpen: () => { setConnected(true); setConnectionLabel('已连接') },
      onClose: () => { setConnected(false); setConnectionLabel('连接已关闭'); clientRef.current = null },
      onError: (reason) => { setError(reason.message); setConnectionLabel('连接失败') },
    })
    clientRef.current = client
    if (client.socket.readyState === WebSocket.OPEN) {
      setConnected(true)
      setConnectionLabel('已连接')
    }
  }

  async function createSession() {
    if (!profile) return
    setBusy(true)
    setError('')
    try {
      const runtime = await createPlaygroundConversation({
        agentId: profile.id,
        voiceId: profile.ttsVoiceId ?? undefined,
        modelOverrides: selectedModels,
      })
      const local = newLocalSession(profile, runtime, selectedModels)
      persist(local)
      attachRuntime(runtime)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '操练会话创建失败')
    } finally {
      setBusy(false)
    }
  }

  async function resumeSession(session: PlaygroundSession) {
    setProfileId(session.snapshot.profileId)
    setSelectedModels(session.snapshot.models)
    persist(session)
    setBusy(true)
    try {
      const runtime = await createPlaygroundConversation({
        agentId: session.snapshot.profileId,
        voiceId: session.snapshot.ttsVoiceId ?? undefined,
        modelOverrides: session.snapshot.models,
      })
      const next = { ...session, playgroundSessionId: runtime.conversationId, agentVersion: runtime.agentVersion }
      persist(next)
      attachRuntime(runtime)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '会话恢复失败')
    } finally {
      setBusy(false)
    }
  }

  function sendText() {
    const text = draft.trim()
    if (!text || !clientRef.current || !connected) return
    const requestId = crypto.randomUUID()
    const messageItem: PlaygroundMessage = {
      id: requestId, requestId, role: 'user', text, createdAt: new Date().toISOString(), capability: 'text',
    }
    updateActive((session) => ({ ...session, messages: [...session.messages, messageItem] }))
    setDraft('')
    setBusy(true)
    try { clientRef.current.sendText(requestId, text) }
    catch (reason) { setBusy(false); messageApi.error(reason instanceof Error ? reason.message : '发送失败') }
  }

  async function toggleRecording() {
    if (recording) { recorderRef.current?.stop(); return }
    if (!clientRef.current || !connected) return
    if (!navigator.mediaDevices?.getUserMedia) { messageApi.error('当前浏览器不支持麦克风'); return }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true } })
      const recorder = new MediaRecorder(stream)
      const chunks: Blob[] = []
      recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data) }
      recorder.onstop = async () => {
        stream.getTracks().forEach((track) => track.stop())
        try {
          const blob = new Blob(chunks, { type: recorder.mimeType })
          const context = new AudioContext()
          const decoded = await context.decodeAudioData(await blob.arrayBuffer())
          const samples = resamplePcm(decoded.getChannelData(0), decoded.sampleRate)
          const pcm = encodePcm(samples)
          const durationMs = Math.max(1, Math.round(samples.length / 16_000 * 1000))
          const requestId = crypto.randomUUID()
          const localUrl = URL.createObjectURL(blob)
          updateActive((session) => ({ ...session, messages: [...session.messages, {
            id: requestId, requestId, role: 'user', text: '语音消息', audioDataUrl: localUrl,
            createdAt: new Date().toISOString(), capability: 'audio',
          }] }))
          setBusy(true)
          clientRef.current?.sendAudio(requestId, pcm, durationMs)
          await context.close()
        } catch (reason) {
          messageApi.error(reason instanceof Error ? reason.message : '语音发送失败')
        } finally {
          setRecording(false)
        }
      }
      recorderRef.current = recorder
      recorder.start()
      setRecording(true)
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '无法开始录音')
    }
  }

  const displayedProfile = active
    ? profiles.find((item) => item.id === active.snapshot.profileId) ?? profile
    : profile

  return <>
    {messageContext}
    <div className="page-heading"><div><h1>操练场</h1><Typography.Text type="secondary">公共对话链路测试</Typography.Text></div></div>
    {error && <Alert type="error" showIcon message={error} closable onClose={() => setError('')} />}
    <div className="playground-grid">
      <Card title="历史对话" extra={<Button aria-label="新建操练会话" icon={<SettingOutlined />} onClick={() => {
        clientRef.current?.close(); clientRef.current = null; activeRef.current = null; setActive(null); setConnected(false); setConnectionLabel('未连接')
      }}>新建</Button>} className="playground-history">
        <List locale={{ emptyText: <Empty description="暂无本地会话" /> }} dataSource={sessions} renderItem={(item) => <List.Item actions={[
          <Button key="delete" aria-label={`删除 ${item.title}`} type="text" icon={<DeleteOutlined />} onClick={() => {
            removeSession(item.id)
            setSessions((current) => current.filter((entry) => entry.id !== item.id))
            if (activeRef.current?.id === item.id) { activeRef.current = null; setActive(null) }
          }} />,
        ]}><Button type="text" className="playground-history-item" onClick={() => void resumeSession(item)}>{item.title}<Typography.Text type="secondary">{new Date(item.createdAt).toLocaleDateString()}</Typography.Text></Button></List.Item>} />
      </Card>

      <Card title={active?.title ?? '新建对话会话'} extra={<Space size="small"><Tag color={connected ? 'green' : 'default'}>{connectionLabel}</Tag>{active && !connected && <Button aria-label="重新连接会话" type="text" icon={<ReloadOutlined />} loading={busy} onClick={() => void resumeSession(active)} />}</Space>} className="playground-conversation">
        {!active ? <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Paragraph>选择一个已发布角色，通过新版公共对话接口测试完整链路。</Typography.Paragraph>
          <Select aria-label="陪伴角色" value={profileId || undefined} placeholder="选择陪伴角色" options={profiles.map((item) => ({ label: item.name, value: item.id }))} loading={loading} onChange={setProfileId} />
          <Button type="primary" loading={busy} onClick={() => void createSession()}>创建对话会话</Button>
        </Space> : <>
          <div className="playground-runtime-summary">
            <Typography.Text>{active.snapshot.profileName}</Typography.Text>
            <Typography.Text type="secondary">Agent v{active.agentVersion ?? '--'} · {active.playgroundSessionId?.slice(0, 8) ?? '--'}</Typography.Text>
          </div>
          <div className="playground-message-list">
            {active.messages.map((item) => <div className={`playground-message playground-message-${item.role}`} key={item.id}>
              {item.audioDataUrl && <audio className="playground-audio" controls src={item.audioDataUrl}>当前浏览器不支持音频播放</audio>}
              <span>{item.text}</span>
            </div>)}
            {active.events.map((event) => {
              const transcript = typeof event.details?.transcript === 'string' ? event.details.transcript : null
              const resultAudio = typeof event.details?.audioDataUrl === 'string' ? event.details.audioDataUrl : null
              const spokenText = typeof event.details?.text === 'string' ? event.details.text : null
              return <div className={`playground-event playground-event-${event.capability}`} key={`${event.sessionId}-${event.sequence}-${event.stage}`}>
                <div><Tag color={event.status === 'failed' ? 'red' : 'blue'}>{event.capability}</Tag>{event.status === 'failed' ? event.error : event.outputSummary}</div>
                {transcript && <Typography.Paragraph copyable className="playground-transcript">识别文字：{transcript}</Typography.Paragraph>}
                {spokenText && event.capability === 'tts' && <Typography.Paragraph className="playground-spoken-text">语音内容：{spokenText}</Typography.Paragraph>}
                {resultAudio && <audio className="playground-audio" controls src={resultAudio}>当前浏览器不支持音频播放</audio>}
              </div>
            })}
          </div>
          <Space.Compact block>
            <Input aria-label="消息" value={draft} disabled={!connected} onChange={(event) => setDraft(event.target.value)} onPressEnter={sendText} placeholder={connected ? '输入消息' : '等待连接'} />
            <Button aria-label="语音对话" disabled={!connected} icon={<AudioOutlined />} onClick={() => void toggleRecording()}>{recording ? '停止录音' : ''}</Button>
            <Button aria-label="发送消息" disabled={!connected} type="primary" icon={<SendOutlined />} loading={busy} onClick={sendText} />
          </Space.Compact>
        </>}
      </Card>

      <Card title="运行配置" className="playground-configuration"><div className="playground-config-scroll">
        <Typography.Text strong>陪伴角色</Typography.Text>
        <Select aria-label="当前角色" value={profileId || undefined} options={profiles.map((item) => ({ label: item.name, value: item.id }))} onChange={setProfileId} disabled={Boolean(active)} />
        <Typography.Text strong>模型覆盖</Typography.Text>
        {modelTypes.map((type) => {
          const selectedOption = (modelOptionsByType[type] ?? []).find((item) => item.id === selectedModels[type])
          return <div className="playground-model-row" key={type}><Typography.Text type="secondary">{type}</Typography.Text><div>
            <Select aria-label={`${type} 模型`} allowClear disabled={Boolean(active)} value={selectedModels[type] || undefined} placeholder={`沿用角色 ${type}`} options={(modelOptionsByType[type] ?? []).map((item) => ({ label: item.name, value: item.id, disabled: !item.enabled }))} onChange={(value) => setSelectedModels((current) => ({ ...current, [type]: value ?? '' }))} />
            {selectedOption?.unavailableReason && <Typography.Text type="danger" className="playground-model-warning">{selectedOption.unavailableReason}</Typography.Text>}
          </div></div>
        })}
        <Typography.Text strong>角色提示词</Typography.Text>
        <Typography.Paragraph className="playground-config-value">{displayedProfile?.personality || '未设置'}</Typography.Paragraph>
        <Typography.Text strong>系统提示词</Typography.Text>
        <Typography.Paragraph className="playground-config-value">{displayedProfile?.systemPrompt || '未设置'}</Typography.Paragraph>
        <Typography.Text strong>已发布技能</Typography.Text>
        <Space wrap>{displayedProfile?.skills?.filter((skill) => skill.enabled).map((skill) => <Tag key={skill.skillId}>{skill.skillId}</Tag>) ?? <Typography.Text type="secondary">未绑定</Typography.Text>}</Space>
        <Typography.Text strong>音色</Typography.Text>
        <Typography.Text>{displayedProfile?.ttsVoiceName || displayedProfile?.ttsVoiceId || '沿用角色默认音色'}</Typography.Text>
      </div></Card>
    </div>
  </>
}
