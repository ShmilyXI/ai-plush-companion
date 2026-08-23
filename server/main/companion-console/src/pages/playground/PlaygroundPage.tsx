import { AudioOutlined, CameraOutlined, DeleteOutlined, SendOutlined, SettingOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Empty, Input, InputNumber, List, Modal, Select, Space, Tag, Typography, Upload, message } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { listProfileModelOptions, listProfiles, type CompanionProfile, type ProfileModelOption } from '../../api/profiles'
import { createPlaygroundSession, getPlaygroundSession, sendPlaygroundInput, streamPlaygroundEvents, type PlaygroundSessionCreated } from '../../api/playground'
import { loadPlaygroundStore, removeSession, upsertSession } from './playgroundStorage'
import type { PlaygroundInputKind, PlaygroundSession, VirtualDeviceState } from './playgroundTypes'

const defaultDevice: VirtualDeviceState = { width: 240, height: 240, depth: 8, orientation: 'square', screen: true, camera: true, microphone: true, activitySensor: true }
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

function newLocalSession(profile: CompanionProfile, snapshot: PlaygroundSessionCreated | null, device: VirtualDeviceState, models: Record<string, string>, rolePrompt: string, systemPrompt: string, skills: string[]): PlaygroundSession {
  return { id: crypto.randomUUID(), title: `${profile.name} · 操练会话`, createdAt: new Date().toISOString(), playgroundSessionId: snapshot?.sessionId ?? null, runtimeCursor: 0, snapshot: { profileId: profile.id, profileName: profile.name, models, ttsVoiceId: profile.ttsVoiceId, skills, rolePrompt, systemPrompt, virtualDevice: device }, messages: [], events: [], screenState: {}, memories: [] }
}

export function PlaygroundPage() {
  const [profiles, setProfiles] = useState<CompanionProfile[]>([])
  const [sessions, setSessions] = useState<PlaygroundSession[]>(() => loadPlaygroundStore().sessions)
  const [active, setActive] = useState<PlaygroundSession | null>(null)
  const [profileId, setProfileId] = useState('')
  const [modelOptions, setModelOptions] = useState<ProfileModelOption[]>([])
  const [selectedModels, setSelectedModels] = useState<Record<string, string>>({})
  const [rolePrompt, setRolePrompt] = useState('')
  const [systemPrompt, setSystemPrompt] = useState('')
  const [selectedSkills, setSelectedSkills] = useState<string[]>([])
  const [memoryOpen, setMemoryOpen] = useState(false)
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
  const modelOptionsByType = useMemo(() => Object.fromEntries(modelTypes.map((type) => [type, modelOptions.filter((item) => item.modelType === type)])), [modelOptions])

  const activeSnapshot = active?.snapshot
  useEffect(() => {
    if (!profile) return
    setRolePrompt(activeSnapshot?.rolePrompt ?? profile.personality ?? '')
    setSystemPrompt(activeSnapshot?.systemPrompt ?? profile.systemPrompt ?? '')
    setSelectedSkills(activeSnapshot?.skills ?? profile.skills?.map((skill) => skill.skillId) ?? [])
    setSelectedModels(activeSnapshot?.models ?? Object.fromEntries((profile.effectiveModels ?? []).map((model) => [model.modelType, model.resourceId ?? ''])))
  }, [active?.id, activeSnapshot?.models, activeSnapshot?.rolePrompt, activeSnapshot?.skills, activeSnapshot?.systemPrompt, profile])

  function persist(next: PlaygroundSession) { setActive(next); setSessions((current) => [next, ...current.filter((item) => item.id !== next.id)]); upsertSession(next) }
  async function createSession() {
    if (!profile) return
    setBusy(true); setError('')
    try {
      const snapshot = await createPlaygroundSession({ profileId: profile.id, models: selectedModels, ttsVoiceId: profile.ttsVoiceId ?? undefined, skillIds: selectedSkills, rolePrompt, systemPrompt, virtualDevice: { ...device } })
      persist(newLocalSession(profile, snapshot, device, selectedModels, rolePrompt, systemPrompt, selectedSkills))
    } catch (reason) { setError(reason instanceof Error ? reason.message : '操练会话创建失败') } finally { setBusy(false) }
  }
  async function ensureRuntimeSession(session: PlaygroundSession) {
    if (session.playgroundSessionId) {
      try { await getPlaygroundSession(session.playgroundSessionId); return session }
      catch { /* The browser may hold a session that expired during a backend restart. */ }
    }
    const snapshot = await createPlaygroundSession({ profileId: session.snapshot.profileId, models: session.snapshot.models, ttsVoiceId: session.snapshot.ttsVoiceId ?? undefined, skillIds: session.snapshot.skills, rolePrompt: session.snapshot.rolePrompt, systemPrompt: session.snapshot.systemPrompt, virtualDevice: { ...session.snapshot.virtualDevice } })
    const refreshed = { ...session, playgroundSessionId: snapshot.sessionId, runtimeCursor: 0 }
    persist(refreshed)
    return refreshed
  }

  function applyDraftConfig(session: PlaygroundSession) {
    const snapshot = {
      ...session.snapshot,
      models: selectedModels,
      rolePrompt,
      systemPrompt,
      skills: selectedSkills,
    }
    const changed = JSON.stringify({ models: session.snapshot.models, rolePrompt: session.snapshot.rolePrompt, systemPrompt: session.snapshot.systemPrompt, skills: session.snapshot.skills })
      !== JSON.stringify({ models: snapshot.models, rolePrompt: snapshot.rolePrompt, systemPrompt: snapshot.systemPrompt, skills: snapshot.skills })
    return changed
      ? { ...session, snapshot, playgroundSessionId: null, runtimeCursor: 0, events: [] }
      : { ...session, snapshot }
  }

  async function runInput(kind: PlaygroundInputKind, payload: Record<string, unknown>) {
    if (!active) return
    setBusy(true)
    const text = typeof payload.text === 'string' ? payload.text : `${kind} 测试`
    let next: PlaygroundSession = applyDraftConfig({ ...active, messages: [...active.messages, { id: crypto.randomUUID(), role: 'user' as const, text, createdAt: new Date().toISOString() }] })
    persist(next)
    try {
      next = await ensureRuntimeSession(next)
      await sendPlaygroundInput(next.playgroundSessionId!, { kind, ...payload } as never)
      await streamPlaygroundEvents(next.playgroundSessionId!, next.runtimeCursor ?? 0, { onEvent: (event) => {
        const transcript = typeof event.details?.transcript === 'string' ? event.details.transcript : null
        const ttsText = typeof event.details?.text === 'string' ? event.details.text : null
        const hasLlmReply = next.events.some((item) => item.capability === 'llm' && item.inputSummary === event.inputSummary)
        const messages = [
          ...(transcript ? [{ id: crypto.randomUUID(), role: 'user' as const, text: transcript, createdAt: new Date().toISOString(), capability: 'asr' }] : []),
          ...(event.capability === 'llm' ? [{ id: crypto.randomUUID(), role: 'assistant' as const, text: event.outputSummary, createdAt: new Date().toISOString(), capability: 'llm' }] : []),
          ...(event.capability === 'tts' && ttsText && !hasLlmReply ? [{ id: crypto.randomUUID(), role: 'assistant' as const, text: ttsText, createdAt: new Date().toISOString(), capability: 'tts' }] : []),
        ]
        next = { ...next, messages: [...next.messages, ...messages], events: [...next.events, event], runtimeCursor: Math.max(next.runtimeCursor ?? 0, event.sequence), memories: event.capability === 'memory' ? [...next.memories, event.inputSummary] : next.memories }
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
      const audioContext = new AudioContext()
      const audio = await audioContext.decodeAudioData(await (await new Blob(chunks).arrayBuffer()).slice(0))
      const samples = resamplePcm(audio.getChannelData(0), audio.sampleRate); const pcm = new Int16Array(samples.length)
      for (let index = 0; index < samples.length; index += 1) pcm[index] = Math.max(-1, Math.min(1, samples[index])) * 0x7fff
      let binary = ''; const bytes = new Uint8Array(pcm.buffer); for (const byte of bytes) binary += String.fromCharCode(byte)
      await audioContext.close()
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
        {!active ? <Space direction="vertical" size="middle" style={{ width: '100%' }}><Typography.Paragraph>选择一个陪伴角色，创建独立的虚拟设备会话。</Typography.Paragraph><Select aria-label="陪伴角色" value={profileId || undefined} placeholder="选择陪伴角色" options={profiles.map((item) => ({ label: item.name, value: item.id }))} loading={loading} onChange={setProfileId} /><Button type="primary" loading={busy} onClick={() => void createSession()}>创建虚拟会话</Button></Space> : <><div className="playground-message-list">{active.messages.map((item) => <div className={`playground-message playground-message-${item.role}`} key={item.id}>{item.text}</div>)}{active.events.filter((event) => event.stage !== 'input').map((event) => { const transcript = typeof event.details?.transcript === 'string' ? event.details.transcript : null; const audioDataUrl = typeof event.details?.audioDataUrl === 'string' ? event.details.audioDataUrl : null; const spokenText = typeof event.details?.text === 'string' ? event.details.text : null; return <div className={`playground-event playground-event-${event.capability}`} key={`${event.sessionId}-${event.sequence}`}><div><Tag color={event.status === 'failed' ? 'red' : 'blue'}>{event.capability}</Tag>{event.status === 'failed' ? event.error : event.outputSummary}</div>{transcript && <Typography.Paragraph copyable className="playground-transcript">识别文字：{transcript}</Typography.Paragraph>}{spokenText && <Typography.Paragraph className="playground-spoken-text">语音内容：{spokenText}</Typography.Paragraph>}{audioDataUrl && <audio className="playground-audio" controls src={audioDataUrl}>当前浏览器不支持音频播放</audio>}</div> })}</div><div className="playground-screen" style={{ width: Math.min(active.snapshot.virtualDevice.width, 360), height: Math.min(active.snapshot.virtualDevice.height, 220) }}><span>模拟设备屏幕</span><small>{active.snapshot.virtualDevice.width} × {active.snapshot.virtualDevice.height} · {active.snapshot.virtualDevice.orientation}</small></div><Space.Compact block><Input aria-label="消息" value={draft} onChange={(event) => setDraft(event.target.value)} onPressEnter={() => void sendText()} placeholder="输入消息" /><Button aria-label="语音对话" icon={<AudioOutlined />} onClick={() => void toggleRecording()}>{recording ? '停止录音' : ''}</Button><Button aria-label="发送消息" type="primary" icon={<SendOutlined />} loading={busy} onClick={() => void sendText()} /></Space.Compact><div className="playground-capability-toolbar"><Button icon={<AudioOutlined />} disabled={busy} onClick={() => void runInput('tts', { text: '你好，这是当前音色的试听效果。' })}>测 TTS</Button><Button disabled={busy} onClick={() => void sendActivity()}>活动感知</Button><Upload showUploadList={false} beforeUpload={async (file) => { const data = await new Promise<string>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = () => reject(reader.error); reader.readAsDataURL(file) }); await runInput('vision', { imageRef: data }); return false }}><Button icon={<CameraOutlined />} disabled={busy}>上传图片测视觉</Button></Upload><Button onClick={() => setMemoryOpen(true)}>查看临时记忆</Button></div></>}
      </Card>
      <Card title="运行配置" className="playground-configuration"><div className="playground-config-scroll"><Typography.Text strong>陪伴角色</Typography.Text><Select aria-label="当前角色" value={profile?.id} options={profiles.map((item) => ({ label: item.name, value: item.id }))} onChange={setProfileId} /><Typography.Text strong>模型切换</Typography.Text>{modelTypes.map((type) => { const selectedOption = (modelOptionsByType[type] ?? []).find((item) => item.id === selectedModels[type]); return <div className="playground-model-row" key={type}><Typography.Text type="secondary">{type}</Typography.Text><div><Select aria-label={`${type} 模型`} allowClear value={selectedModels[type] || undefined} placeholder={`选择 ${type} 模型`} options={(modelOptionsByType[type] ?? []).map((item) => ({ label: item.name, value: item.id, disabled: !item.enabled }))} onChange={(value) => setSelectedModels((current) => ({ ...current, [type]: value ?? '' }))} />{selectedOption?.unavailableReason && <Typography.Text type="danger" className="playground-model-warning">{selectedOption.unavailableReason}</Typography.Text>}</div></div> })}<Typography.Text strong>角色提示词</Typography.Text><Input.TextArea aria-label="角色提示词" value={rolePrompt} autoSize={{ minRows: 3, maxRows: 6 }} onChange={(event) => setRolePrompt(event.target.value)} /><Typography.Text strong>系统提示词</Typography.Text><Input.TextArea aria-label="系统提示词" value={systemPrompt} autoSize={{ minRows: 3, maxRows: 6 }} onChange={(event) => setSystemPrompt(event.target.value)} /><Typography.Text strong>技能</Typography.Text><Select aria-label="技能" mode="tags" value={selectedSkills} onChange={setSelectedSkills} options={(profile?.skills ?? []).map((skill) => ({ label: skill.skillId, value: skill.skillId }))} placeholder="选择或输入技能" /><Typography.Text strong>模拟设备尺寸</Typography.Text><Space wrap><InputNumber aria-label="屏幕宽度" min={1} value={device.width} onChange={(value) => setDevice((current) => ({ ...current, width: value ?? current.width }))} /><InputNumber aria-label="屏幕高度" min={1} value={device.height} onChange={(value) => setDevice((current) => ({ ...current, height: value ?? current.height }))} /><InputNumber aria-label="设备厚度" min={1} value={device.depth} onChange={(value) => setDevice((current) => ({ ...current, depth: value ?? current.depth }))} /></Space></div><Modal title="临时记忆" open={memoryOpen} onCancel={() => setMemoryOpen(false)} footer={null}><List dataSource={active?.memories ?? []} locale={{ emptyText: '本次会话还没有记忆候选' }} renderItem={(memory) => <List.Item>{memory}</List.Item>} /></Modal></Card>
    </div>
  </>
}
