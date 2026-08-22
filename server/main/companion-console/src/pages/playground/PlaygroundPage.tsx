import { AudioOutlined, CameraOutlined, DeleteOutlined, SendOutlined, SettingOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Empty, Input, InputNumber, List, Select, Space, Tag, Typography, Upload, message } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { listProfileModelOptions, listProfiles, type CompanionProfile, type ProfileModelOption } from '../../api/profiles'
import { createPlaygroundSession, sendPlaygroundInput, streamPlaygroundEvents, type PlaygroundSessionCreated } from '../../api/playground'
import { loadPlaygroundStore, removeSession, upsertSession } from './playgroundStorage'
import type { PlaygroundInputKind, PlaygroundSession, VirtualDeviceState } from './playgroundTypes'

const defaultDevice: VirtualDeviceState = { width: 240, height: 240, depth: 8, orientation: 'square', screen: true, camera: true, microphone: true, activitySensor: true }

function newLocalSession(profile: CompanionProfile, snapshot: PlaygroundSessionCreated | null, device: VirtualDeviceState): PlaygroundSession {
  return { id: crypto.randomUUID(), title: `${profile.name} · 操练会话`, createdAt: new Date().toISOString(), playgroundSessionId: snapshot?.sessionId ?? null, snapshot: { profileId: profile.id, profileName: profile.name, models: {}, ttsVoiceId: profile.ttsVoiceId, skills: profile.skills?.map((skill) => skill.skillId) ?? [], virtualDevice: device }, messages: [], events: [], screenState: {}, memories: [] }
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
  const [error, setError] = useState('')
  const [messageApi, messageContext] = message.useMessage()

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
  async function runInput(kind: PlaygroundInputKind, payload: Record<string, unknown>) {
    if (!active?.playgroundSessionId) return
    setBusy(true)
    try {
      await sendPlaygroundInput(active.playgroundSessionId, { kind, ...payload } as never)
      const text = typeof payload.text === 'string' ? payload.text : `${kind} 测试`
      let next = { ...active, messages: [...active.messages, { id: crypto.randomUUID(), role: 'user' as const, text, createdAt: new Date().toISOString() }] }
      persist(next)
      await streamPlaygroundEvents(active.playgroundSessionId, active.events.at(-1)?.sequence ?? 0, { onEvent: (event) => {
        const assistant = event.capability === 'llm' ? [{ id: crypto.randomUUID(), role: 'assistant' as const, text: event.outputSummary, createdAt: new Date().toISOString(), capability: 'llm' }] : []
        next = { ...next, messages: [...next.messages, ...assistant], events: [...next.events, event], memories: event.capability === 'memory' ? [...next.memories, event.inputSummary] : next.memories }
        persist(next)
      } })
    } catch (reason) { messageApi.error(reason instanceof Error ? reason.message : '发送失败') } finally { setBusy(false) }
  }
  async function sendText() { const text = draft.trim(); if (!text) return; setDraft(''); await runInput('text', { text }) }
  async function sendActivity() { await runInput('activity', { activity: { state: 'walking', intensity: 0.6 } }) }
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
      <Card title="运行配置" className="playground-configuration"><Typography.Text strong>陪伴角色</Typography.Text><Select aria-label="当前角色" value={profile?.id} options={profiles.map((item) => ({ label: item.name, value: item.id }))} onChange={setProfileId} /><Typography.Text strong>模型目录</Typography.Text><List size="small" loading={loading && Boolean(profileId)} dataSource={modelOptions} locale={{ emptyText: '当前角色没有可用模型' }} renderItem={(item) => <List.Item><span>{item.modelType} · {item.name}</span><Tag color={item.credentialStatus === 'configured' || item.credentialStatus === 'not_required' ? 'green' : 'orange'}>{item.credentialStatus}</Tag></List.Item>} /><Typography.Text strong>模拟设备尺寸</Typography.Text><Space wrap><InputNumber aria-label="屏幕宽度" min={1} value={device.width} onChange={(value) => setDevice((current) => ({ ...current, width: value ?? current.width }))} /><InputNumber aria-label="屏幕高度" min={1} value={device.height} onChange={(value) => setDevice((current) => ({ ...current, height: value ?? current.height }))} /><InputNumber aria-label="设备厚度" min={1} value={device.depth} onChange={(value) => setDevice((current) => ({ ...current, depth: value ?? current.depth }))} /></Space><Typography.Text strong>能力测试</Typography.Text><Space wrap><Button icon={<AudioOutlined />} disabled={!active} onClick={() => void runInput('audio', { audioRef: 'browser-microphone' })}>测 ASR</Button><Button icon={<AudioOutlined />} disabled={!active} onClick={() => void runInput('text', { text: '试听当前音色' })}>测 TTS</Button><Button disabled={!active} onClick={() => void sendActivity()}>活动感知</Button><Button icon={<CameraOutlined />} disabled={!active} onClick={() => messageApi.info('视觉理解需要上传图片')}>视觉理解</Button><Upload showUploadList={false} beforeUpload={() => { void runInput('vision', { imageRef: 'browser-upload' }); return false }}><Button icon={<CameraOutlined />} disabled={!active}>上传图片</Button></Upload></Space><Typography.Paragraph type="secondary">角色提示词、系统提示词、模型、技能和音色来自角色配置。当前右侧修改只影响下一次快照。</Typography.Paragraph></Card>
    </div>
  </>
}
