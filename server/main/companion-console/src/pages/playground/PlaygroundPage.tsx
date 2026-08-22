import { AudioOutlined, CameraOutlined, DeleteOutlined, SendOutlined, SettingOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Empty, Input, InputNumber, List, Select, Space, Tag, Typography, Upload, message } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { listProfileModelOptions, listProfiles, type CompanionProfile, type ProfileModelOption } from '../../api/profiles'
import { createPlaygroundSession, getPlaygroundSession, sendPlaygroundInput, streamPlaygroundEvents, type PlaygroundSessionCreated } from '../../api/playground'
import { loadPlaygroundStore, removeSession, upsertSession } from './playgroundStorage'
import type { PlaygroundInputKind, PlaygroundSession, VirtualDeviceState } from './playgroundTypes'

const defaultDevice: VirtualDeviceState = { width: 240, height: 240, depth: 8, orientation: 'square', screen: true, camera: true, microphone: true, activitySensor: true }

function newLocalSession(profile: CompanionProfile, snapshot: PlaygroundSessionCreated | null, device: VirtualDeviceState): PlaygroundSession {
  return { id: crypto.randomUUID(), title: `${profile.name} · 操练会话`, createdAt: new Date().toISOString(), playgroundSessionId: snapshot?.sessionId ?? null, runtimeCursor: 0, snapshot: { profileId: profile.id, profileName: profile.name, models: {}, ttsVoiceId: profile.ttsVoiceId, skills: profile.skills?.map((skill) => skill.skillId) ?? [], virtualDevice: device }, messages: [], events: [], screenState: {}, memories: [] }
}

export function PlaygroundPage() {
  const [profiles, setProfiles] = useState<CompanionProfile[]>([])
  const [sessions, setSessions] = useState<PlaygroundSession[]>(() => loadPlaygroundStore().sessions)
  const [active, setActive] = useState<PlaygroundSession | null>(null)
  const [profileId, setProfileId] = useState('')
  const [modelOptions, setModelOptions] = useState<ProfileModelOption[]>([])
  const [draft, setDraft] = useState('')
  const [device, setDevice] = useState(defaultDevice)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [recording, setRecording] = useState(false)
  const [error, setError] = useState('')
  const [messageApi, messageContext] = message.useMessage()
  const recorderRef = useState<{ current: MediaRecorder | null }>({ current: null })[0]

  useEffect(() => { void listProfiles().then((items) => { setProfiles(items); setProfileId(items[0]?.id ?? '') }).catch((reason) => setError(reason instanceof Error ? reason.message : '角色列表加载失败')).finally(() => setLoading(false)) }, [])
  useEffect(() => {
    if (!profileId) { setModelOptions([]); return }
    const controller = new AbortController()
    void listProfileModelOptions(profileId, { signal: controller.signal }).then(setModelOptions).catch((reason) => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '模型列表加载失败')
    })
    return () => controller.abort()
  }, [profileId])
  const profile = useMemo(() => profiles.find((item) => item.id === (active?.snapshot.profileId || profileId)) ?? null, [active, profileId, profiles])

  function persist(next: PlaygroundSession) { setActive(next); setSessions((current) => [next, ...current.filter((item) => item.id !== next.id)]); upsertSession(next) }
  async function createSession() {
    if (!profile) return
    setBusy(true); setError('')
    try {
      const snapshot = await createPlaygroundSession({ profileId: profile.id, ttsVoiceId: profile.ttsVoiceId ?? undefined, skillIds: profile.skills?.map((skill) => skill.skillId), virtualDevice: { ...device } })
      persist(newLocalSession(profile, snapshot, device))
    } catch (reason) { setError(reason instanceof Error ? reason.message : '操练会话创建失败') } finally { setBusy(false) }
  }
  async function ensureRuntimeSession(session: PlaygroundSession) {
    if (session.playgroundSessionId) {
      try { await getPlaygroundSession(session.playgroundSessionId); return session }
      catch { /* The browser may hold a session that expired during a backend restart. */ }
    }
    const snapshot = await createPlaygroundSession({ profileId: session.snapshot.profileId, ttsVoiceId: session.snapshot.ttsVoiceId ?? undefined, skillIds: session.snapshot.skills, virtualDevice: { ...session.snapshot.virtualDevice } })
    const refreshed = { ...session, playgroundSessionId: snapshot.sessionId, runtimeCursor: 0 }
    persist(refreshed)
    return refreshed
  }

  async function runInput(kind: PlaygroundInputKind, payload: Record<string, unknown>) {
    if (!active) return
    setBusy(true)
    const text = typeof payload.text === 'string' ? payload.text : `${kind} 测试`
    let next = { ...active, messages: [...active.messages, { id: crypto.randomUUID(), role: 'user' as const, text, createdAt: new Date().toISOString() }] }
    persist(next)
    try {
      next = await ensureRuntimeSession(next)
      await sendPlaygroundInput(next.playgroundSessionId!, { kind, ...payload } as never)
      await streamPlaygroundEvents(next.playgroundSessionId!, next.runtimeCursor ?? 0, { onEvent: (event) => {
        const assistant = event.capability === 'llm' ? [{ id: crypto.randomUUID(), role: 'assistant' as const, text: event.outputSummary, createdAt: new Date().toISOString(), capability: 'llm' }] : []
        next = { ...next, messages: [...next.messages, ...assistant], events: [...next.events, event], runtimeCursor: Math.max(next.runtimeCursor ?? 0, event.sequence), memories: event.capability === 'memory' ? [...next.memories, event.inputSummary] : next.memories }
        persist(next)
      } })
    } catch (reason) {
      const errorText = reason instanceof Error ? reason.message : '发送失败'
      const event = { sessionId: next.playgroundSessionId ?? '', sequence: (next.runtimeCursor ?? 0) + 1, capability: kind, stage: 'request', status: 'failed' as const, startedAt: Date.now(), finishedAt: Date.now(), durationMs: 0, inputSummary: text, outputSummary: '', error: errorText }
      next = { ...next, events: [...next.events, event] }
      persist(next)
      messageApi.error(errorText)
    } finally { setBusy(false) }
  }
  async function sendText() { const text = draft.trim(); if (!text) return; setDraft(''); await runInput('text', { text }) }
  async function sendActivity() { await runInput('activity', { activity: { state: 'walking', intensity: 0.6 } }) }
  async function toggleRecording() {
    if (recording) { recorderRef.current?.stop(); return }
    if (!navigator.mediaDevices?.getUserMedia) { messageApi.error('当前浏览器不支持麦克风'); return }
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const recorder = new MediaRecorder(stream)
    const chunks: Blob[] = []
    recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data) }
    recorder.onstop = async () => {
      stream.getTracks().forEach((track) => track.stop())
      const audio = await new AudioContext().decodeAudioData(await (await new Blob(chunks).arrayBuffer()).slice(0))
      const samples = audio.getChannelData(0); const pcm = new Int16Array(samples.length)
      for (let index = 0; index < samples.length; index += 1) pcm[index] = Math.max(-1, Math.min(1, samples[index])) * 0x7fff
      let binary = ''; const bytes = new Uint8Array(pcm.buffer); for (const byte of bytes) binary += String.fromCharCode(byte)
      await runInput('audio', { audioRef: `data:audio/pcm;base64,${btoa(binary)}` }); setRecording(false)
    }
    recorderRef.current = recorder; recorder.start(); setRecording(true)
  }
  function selectSession(session: PlaygroundSession) { setActive(session); setProfileId(session.snapshot.profileId) }

  return <>
    {messageContext}
    <div className="page-heading"><div><h1>操练场</h1><Typography.Text type="secondary">虚拟设备实验室</Typography.Text></div></div>
    {error && <Alert type="error" showIcon message={error} closable onClose={() => setError('')} />}
    <div className="playground-grid">
      <Card title="历史对话" extra={<Button aria-label="新建操练会话" icon={<SettingOutlined />} onClick={() => setActive(null)}>新建</Button>} className="playground-history">
        <List locale={{ emptyText: <Empty description="暂无本地会话" /> }} dataSource={sessions} renderItem={(item) => <List.Item actions={[<Button aria-label={`删除 ${item.title}`} type="text" icon={<DeleteOutlined />} onClick={() => { removeSession(item.id); setSessions((current) => current.filter((entry) => entry.id !== item.id)); if (active?.id === item.id) setActive(null) }} />]}><Button type="text" className="playground-history-item" onClick={() => selectSession(item)}>{item.title}<Typography.Text type="secondary">{new Date(item.createdAt).toLocaleDateString()}</Typography.Text></Button></List.Item>} />
      </Card>
      <Card title={active?.title ?? '新建虚拟会话'} className="playground-conversation">
        {!active ? <Space direction="vertical" size="middle" style={{ width: '100%' }}><Typography.Paragraph>选择一个陪伴角色，创建独立的虚拟设备会话。</Typography.Paragraph><Select aria-label="陪伴角色" value={profileId || undefined} placeholder="选择陪伴角色" options={profiles.map((item) => ({ label: item.name, value: item.id }))} loading={loading} onChange={setProfileId} /><Button type="primary" loading={busy} onClick={() => void createSession()}>创建虚拟会话</Button></Space> : <><div className="playground-message-list">{active.messages.map((item) => <div className={`playground-message playground-message-${item.role}`} key={item.id}>{item.text}</div>)}{active.events.map((event) => <div className="playground-event" key={`${event.sessionId}-${event.sequence}`}><Tag color={event.status === 'failed' ? 'red' : 'blue'}>{event.capability}</Tag>{event.outputSummary || event.error}</div>)}</div><div className="playground-screen" style={{ width: Math.min(active.snapshot.virtualDevice.width, 360), height: Math.min(active.snapshot.virtualDevice.height, 220) }}><span>模拟设备屏幕</span><small>{active.snapshot.virtualDevice.width} × {active.snapshot.virtualDevice.height} · {active.snapshot.virtualDevice.orientation}</small></div><Space.Compact block><Input aria-label="消息" value={draft} onChange={(event) => setDraft(event.target.value)} onPressEnter={() => void sendText()} placeholder="输入消息" /><Button aria-label="语音对话" icon={<AudioOutlined />} /><Button aria-label="发送消息" type="primary" icon={<SendOutlined />} loading={busy} onClick={() => void sendText()} /></Space.Compact></>}
      </Card>
      <Card title="运行配置" className="playground-configuration"><Typography.Text strong>陪伴角色</Typography.Text><Select aria-label="当前角色" value={profile?.id} options={profiles.map((item) => ({ label: item.name, value: item.id }))} onChange={setProfileId} /><Typography.Text strong>模型目录</Typography.Text><List size="small" loading={loading && Boolean(profileId)} dataSource={modelOptions} locale={{ emptyText: '当前角色没有可用模型' }} renderItem={(item) => <List.Item><span>{item.modelType} · {item.name}</span><Tag color={item.credentialStatus === 'configured' || item.credentialStatus === 'not_required' ? 'green' : 'orange'}>{item.credentialStatus}</Tag></List.Item>} /><Typography.Text strong>提示词与技能</Typography.Text><Typography.Paragraph ellipsis={{ rows: 2 }} type="secondary">{profile?.systemPrompt || '尚未加载系统提示词'}</Typography.Paragraph><Space wrap>{(profile?.skills ?? []).map((skill) => <Tag key={skill.skillId}>{skill.skillId}</Tag>)}</Space><Typography.Text strong>模拟设备尺寸</Typography.Text><Space wrap><InputNumber aria-label="屏幕宽度" min={1} value={device.width} onChange={(value) => setDevice((current) => ({ ...current, width: value ?? current.width }))} /><InputNumber aria-label="屏幕高度" min={1} value={device.height} onChange={(value) => setDevice((current) => ({ ...current, height: value ?? current.height }))} /><InputNumber aria-label="设备厚度" min={1} value={device.depth} onChange={(value) => setDevice((current) => ({ ...current, depth: value ?? current.depth }))} /></Space><Typography.Text strong>能力测试</Typography.Text><Space wrap><Button icon={<AudioOutlined />} disabled={!active} onClick={() => void toggleRecording()}>{recording ? '停止录音' : '语音对话'}</Button><Button icon={<AudioOutlined />} disabled={!active} onClick={() => void runInput('text', { text: '试听当前音色' })}>测 TTS</Button><Button disabled={!active} onClick={() => void sendActivity()}>活动感知</Button><Upload showUploadList={false} beforeUpload={async (file) => { const data = await new Promise<string>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = () => reject(reader.error); reader.readAsDataURL(file) }); await runInput('vision', { imageRef: data }); return false }}><Button icon={<CameraOutlined />} disabled={!active}>上传图片测试视觉</Button></Upload></Space><Typography.Text strong>临时记忆</Typography.Text><List size="small" dataSource={active?.memories ?? []} locale={{ emptyText: '本次会话还没有记忆候选' }} renderItem={(memory) => <List.Item>{memory}</List.Item>} /><Typography.Paragraph type="secondary">角色提示词、系统提示词、模型、技能和音色来自角色配置。当前右侧修改只影响下一次快照。</Typography.Paragraph></Card>
    </div>
  </>
}
