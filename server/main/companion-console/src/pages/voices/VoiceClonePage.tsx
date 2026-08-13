import { EditOutlined, ReloadOutlined, ScissorOutlined, SoundOutlined, UploadOutlined } from '@ant-design/icons'
import { PageContainer } from '@ant-design/pro-components'
import { Alert, Button, Card, Empty, Form, Input, Modal, Space, Steps, Table, Tag, Tooltip, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useRef, useState } from 'react'

import {
  cloneVoiceAudio,
  getVoiceAudioUuid,
  getVoiceClonePlayUrl,
  listVoiceClones,
  updateVoiceCloneName,
  uploadVoiceSample,
  validateVoiceSample,
} from '../../api/voiceClones'
import type { VoiceResource } from '../../api/voiceResources'
import { useAuthStore } from '../../auth/authStore'
import { assertSafeDecodedBuffer, encodeVoiceSampleWav, readSafeVoiceMetadata, trimVoiceBuffer } from './voiceSampleEditor'

const PAGE_SIZE = 10

function errorText(reason: unknown, fallback: string) {
  const text = reason instanceof Error ? reason.message.trim() : ''
  return text && text.length <= 160 ? text : fallback
}

function safeHttpUrl(value: string) {
  try {
    const url = new URL(value)
    return (url.protocol === 'http:' || url.protocol === 'https:') && Boolean(url.hostname)
  } catch {
    return false
  }
}

function cloneStatus(row: VoiceResource) {
  if (!row.hasVoice) return { color: 'default', text: '等待上传' }
  if (row.trainStatus === 0) return { color: 'processing', text: '等待训练' }
  if (row.trainStatus === 1) return { color: 'processing', text: '训练中' }
  if (row.trainStatus === 2) return { color: 'success', text: '训练成功' }
  return { color: 'error', text: '训练失败' }
}

export function VoiceClonePanel() {
  const canClone = useAuthStore((state) => state.hasPermission('sys:role:normal'))
  const isSuperAdmin = useAuthStore((state) => state.hasPermission('sys:role:superAdmin'))
  const [rows, setRows] = useState<VoiceResource[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [name, setName] = useState('')
  const [loading, setLoading] = useState(canClone)
  const [error, setError] = useState('')
  const [uploadingRow, setUploadingRow] = useState<VoiceResource | null>(null)
  const [uploadStep, setUploadStep] = useState<0 | 1>(0)
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [originalUploadFile, setOriginalUploadFile] = useState<File | null>(null)
  const [audioBuffer, setAudioBuffer] = useState<AudioBuffer | null>(null)
  const [originalAudioBuffer, setOriginalAudioBuffer] = useState<AudioBuffer | null>(null)
  const [editorUrl, setEditorUrl] = useState('')
  const [editorPlaying, setEditorPlaying] = useState(false)
  const [selectionStart, setSelectionStart] = useState<number | null>(null)
  const [selectionEnd, setSelectionEnd] = useState<number | null>(null)
  const [selecting, setSelecting] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [editingRow, setEditingRow] = useState<VoiceResource | null>(null)
  const [savingName, setSavingName] = useState(false)
  const [cloningId, setCloningId] = useState<string | null>(null)
  const [playingId, setPlayingId] = useState<string | null>(null)
  const [nameForm] = Form.useForm<{ name: string }>()
  const [messageApi, messageContext] = message.useMessage()
  const mounted = useRef(false)
  const listController = useRef<AbortController | null>(null)
  const requestSequence = useRef(0)
  const previewSequence = useRef(0)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const editorAudioRef = useRef<HTMLAudioElement | null>(null)
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const audioContextRef = useRef<AudioContext | null>(null)
  const editorUrlRef = useRef('')
  const editorSequence = useRef(0)
  const metadataController = useRef<AbortController | null>(null)
  const uploadSession = useRef(0)
  const nameSession = useRef(0)
  const viewRef = useRef({ page, name })
  viewRef.current = { page, name }

  const stopAudio = useCallback(() => {
    previewSequence.current += 1
    const audio = audioRef.current
    if (audio) {
      audio.onerror = null
      audio.onended = null
      audio.pause()
      audio.currentTime = 0
      audio.src = ''
      audioRef.current = null
    }
    setPlayingId(null)
  }, [])

  const stopEditorAudio = useCallback(() => {
    const audio = editorAudioRef.current
    if (audio) {
      audio.pause()
      audio.currentTime = 0
    }
    setEditorPlaying(false)
  }, [])

  const replaceEditorUrl = useCallback((file: File | null) => {
    if (editorUrlRef.current) URL.revokeObjectURL(editorUrlRef.current)
    const next = file ? URL.createObjectURL(file) : ''
    editorUrlRef.current = next
    setEditorUrl(next)
  }, [])

  const resetUploadEditor = useCallback((keepDialog = true) => {
    uploadSession.current += 1
    editorSequence.current += 1
    metadataController.current?.abort()
    metadataController.current = null
    stopEditorAudio()
    replaceEditorUrl(null)
    setUploadStep(0)
    setUploadFile(null)
    setOriginalUploadFile(null)
    setAudioBuffer(null)
    setOriginalAudioBuffer(null)
    setSelectionStart(null)
    setSelectionEnd(null)
    setSelecting(false)
    setUploading(false)
    const context = audioContextRef.current
    audioContextRef.current = null
    if (context) void context.close()
    if (!keepDialog) setUploadingRow(null)
  }, [replaceEditorUrl, stopEditorAudio])

  const load = useCallback(async (nextPage: number, nextName: string) => {
    if (!canClone) return
    const sequence = ++requestSequence.current
    listController.current?.abort()
    const controller = new AbortController()
    listController.current = controller
    setLoading(true)
    setError('')
    try {
      const result = await listVoiceClones({
        page: nextPage, limit: PAGE_SIZE, name: nextName, orderField: 'create_date', order: 'desc',
      }, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || sequence !== requestSequence.current) return
      setRows(result.list)
      setTotal(result.total)
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && sequence === requestSequence.current) {
        setError(errorText(reason, '音色克隆列表加载失败'))
      }
    } finally {
      if (mounted.current && !controller.signal.aborted && sequence === requestSequence.current) setLoading(false)
    }
  }, [canClone])

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      requestSequence.current += 1
      listController.current?.abort()
      stopAudio()
      resetUploadEditor(false)
    }
  }, [resetUploadEditor, stopAudio])

  useEffect(() => {
    if (canClone) void load(page, name)
  }, [canClone, load, name, page])

  useEffect(() => {
    if (uploadStep !== 1 || !audioBuffer) return
    const canvas = canvasRef.current
    const context = canvas?.getContext('2d')
    if (!canvas || !context) return
    const width = canvas.clientWidth || 800
    const height = canvas.clientHeight || 160
    canvas.width = width
    canvas.height = height
    context.clearRect(0, 0, width, height)
    context.fillStyle = '#e6f4ff'
    context.fillRect(0, 0, width, height)
    const samples = audioBuffer.getChannelData(0)
    const bucketSize = Math.max(1, Math.floor(samples.length / width))
    context.fillStyle = '#1677ff'
    for (let x = 0; x < width; x += 1) {
      let peak = 0
      for (let offset = 0; offset < bucketSize; offset += 1) peak = Math.max(peak, Math.abs(samples[x * bucketSize + offset] ?? 0))
      const barHeight = Math.max(1, peak * height * 0.85)
      context.fillRect(x, (height - barHeight) / 2, 1, barHeight)
    }
  }, [audioBuffer, uploadStep])

  async function selectUploadFile(file: File | null) {
    if (!file) return
    const sequence = ++editorSequence.current
    setError('')
    try {
      validateVoiceSample(file)
      metadataController.current?.abort()
      const controller = new AbortController()
      metadataController.current = controller
      await readSafeVoiceMetadata(file, controller.signal)
      if (!mounted.current || sequence !== editorSequence.current) return
      const context = audioContextRef.current ?? new AudioContext()
      audioContextRef.current = context
      const decoded = await context.decodeAudioData((await file.arrayBuffer()).slice(0))
      if (!mounted.current || sequence !== editorSequence.current) return
      assertSafeDecodedBuffer(decoded)
      setOriginalUploadFile(file)
      setUploadFile(file)
      setOriginalAudioBuffer(decoded)
      setAudioBuffer(decoded)
      setSelectionStart(null)
      setSelectionEnd(null)
      replaceEditorUrl(file)
      setUploadStep(1)
    } catch (reason) {
      if (sequence === editorSequence.current) {
        const context = audioContextRef.current
        audioContextRef.current = null
        if (context) void context.close()
        if (mounted.current) setError(errorText(reason, '音频加载失败'))
      }
    }
  }

  async function upload() {
    const session = uploadSession.current
    if (!uploadingRow || !uploadFile) {
      setError('请选择 MP3 或 WAV 音频文件')
      return
    }
    setError('')
    let wavFile: File
    try {
      wavFile = encodeVoiceSampleWav(audioBuffer!)
    } catch (reason) {
      if (mounted.current && uploadSession.current === session) {
        setError(errorText(reason, '音频编码失败'))
      }
      return
    }
    setUploading(true)
    try {
      await uploadVoiceSample(uploadingRow.id, wavFile)
      if (!mounted.current || uploadSession.current !== session) return
      messageApi.success('音频样本已上传')
      resetUploadEditor(false)
      const current = viewRef.current
      await load(current.page, current.name)
    } catch (reason) {
      if (mounted.current && uploadSession.current === session) setError(errorText(reason, '音频上传失败'))
    } finally {
      if (mounted.current && uploadSession.current === session) setUploading(false)
    }
  }

  async function toggleEditorPreview() {
    const audio = editorAudioRef.current
    if (!audio) return
    if (editorPlaying) {
      stopEditorAudio()
      return
    }
    try {
      await audio.play()
      if (mounted.current) setEditorPlaying(true)
    } catch {
      if (mounted.current) setError('样本试听失败')
    }
  }

  function selectionRatio(event: React.MouseEvent<HTMLCanvasElement>) {
    const rect = event.currentTarget.getBoundingClientRect()
    return rect.width > 0 ? Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)) : 0
  }

  function trimSelection() {
    const context = audioContextRef.current
    if (!context || !audioBuffer || selectionStart === null || selectionEnd === null) return
    setError('')
    try {
      const trimmed = trimVoiceBuffer(context, audioBuffer, selectionStart, selectionEnd)
      const file = encodeVoiceSampleWav(trimmed)
      stopEditorAudio()
      setAudioBuffer(trimmed)
      setUploadFile(file)
      setSelectionStart(null)
      setSelectionEnd(null)
      replaceEditorUrl(file)
    } catch (reason) {
      if (mounted.current) setError(errorText(reason, '音频裁剪失败'))
    }
  }

  function resetTrim() {
    if (!originalUploadFile || !originalAudioBuffer) return
    stopEditorAudio()
    setUploadFile(originalUploadFile)
    setAudioBuffer(originalAudioBuffer)
    setSelectionStart(null)
    setSelectionEnd(null)
    replaceEditorUrl(originalUploadFile)
  }

  function editName(row: VoiceResource) {
    nameSession.current += 1
    setEditingRow(row)
    nameForm.setFieldsValue({ name: row.name })
  }

  async function saveName() {
    if (!editingRow) return
    const session = nameSession.current
    let values: { name: string }
    try {
      values = await nameForm.validateFields()
    } catch {
      return
    }
    setSavingName(true)
    setError('')
    try {
      await updateVoiceCloneName(editingRow.id, values.name.trim())
      if (!mounted.current || nameSession.current !== session) return
      messageApi.success('音色名称已更新')
      setEditingRow(null)
      nameForm.resetFields()
      const current = viewRef.current
      await load(current.page, current.name)
    } catch (reason) {
      if (mounted.current && nameSession.current === session) setError(errorText(reason, '音色名称更新失败'))
    } finally {
      if (mounted.current && nameSession.current === session) setSavingName(false)
    }
  }

  async function clone(row: VoiceResource) {
    if (cloningId) return
    setCloningId(row.id)
    setError('')
    try {
      await cloneVoiceAudio(row.id)
      if (!mounted.current) return
      messageApi.success('克隆请求已提交')
      const current = viewRef.current
      await load(current.page, current.name)
    } catch (reason) {
      if (mounted.current) {
        setError(errorText(reason, '克隆失败'))
        const current = viewRef.current
        await load(current.page, current.name)
      }
    } finally {
      if (mounted.current) setCloningId(null)
    }
  }

  async function preview(row: VoiceResource) {
    if (playingId === row.id && audioRef.current) {
      stopAudio()
      return
    }
    stopAudio()
    const sequence = previewSequence.current
    setError('')
    try {
      const uuid = await getVoiceAudioUuid(row.id)
      if (!mounted.current || sequence !== previewSequence.current) return
      const playUrl = getVoiceClonePlayUrl(uuid)
      if (!safeHttpUrl(playUrl)) throw new TypeError('试听地址不安全')
      const audio = new Audio()
      if (!mounted.current || sequence !== previewSequence.current) {
        audio.src = ''
        return
      }
      audioRef.current = audio
      setPlayingId(row.id)
      audio.onerror = () => {
        if (mounted.current && sequence === previewSequence.current) {
          setError('试听失败')
          stopAudio()
        }
      }
      audio.onended = () => {
        if (mounted.current && sequence === previewSequence.current) stopAudio()
      }
      audio.src = playUrl
      await audio.play()
    } catch {
      if (mounted.current && sequence === previewSequence.current) {
        setError('试听失败')
        stopAudio()
      }
    }
  }

  const columns: ColumnsType<VoiceResource> = [
    { title: '名称', dataIndex: 'name' },
    { title: '音色 ID', dataIndex: 'voiceId' },
    { title: '平台', dataIndex: 'modelName' },
    { title: '语言', dataIndex: 'languages', render: (value: string) => value || '未填写' },
    {
      title: '训练状态',
      render: (_, row) => {
        const current = cloneStatus(row)
        return <Space direction="vertical" size={0}>
          <Tag color={current.color}>{current.text}</Tag>
          {row.trainStatus === 3 && row.trainError && <Typography.Text type="danger">{row.trainError}</Typography.Text>}
        </Space>
      },
    },
    {
      title: '操作',
      render: (_, row) => <Space wrap>
        {row.hasVoice && <Button icon={<SoundOutlined />} aria-label={`试听${row.name}`} onClick={() => void preview(row)}>
          {playingId === row.id ? '停止' : '试听'}
        </Button>}
        <Button icon={<UploadOutlined />} aria-label={`上传${row.name}样本`} onClick={() => { resetUploadEditor(); setUploadingRow(row) }}>上传</Button>
        {row.hasVoice && <Tooltip title={row.trainStatus === 3 ? row.trainError || '上次训练失败，可重新提交' : undefined}>
          <Button loading={cloningId === row.id} disabled={Boolean(cloningId && cloningId !== row.id) || row.trainStatus === 1}
            aria-label={`克隆${row.name}`} onClick={() => void clone(row)}>克隆</Button>
        </Tooltip>}
        <Button icon={<EditOutlined />} aria-label={`编辑${row.name}名称`} onClick={() => editName(row)}>编辑名称</Button>
      </Space>,
    },
  ]

  const emptyText = name
    ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未找到匹配的音色资源" />
    : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
      description={isSuperAdmin ? '尚未分配音色资源' : '请联系管理员分配音色资源'} />

  if (!canClone) return <section><Alert type="warning" showIcon message="当前账号无音色克隆权限" /></section>

  return <section>
    {messageContext}
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      {error && <Alert type="error" showIcon message={error} />}
      <Card>
        <Input.Search aria-label="音色名称或 ID" allowClear enterButton="搜索" placeholder="按名称或音色 ID 搜索"
          style={{ maxWidth: 360, marginBottom: 16 }} onSearch={(value) => { stopAudio(); setName(value.trim()); setPage(1) }} />
        <Table rowKey="id" loading={loading} dataSource={rows} columns={columns} locale={{ emptyText }} scroll={{ x: 900 }}
          pagination={{ current: page, total, pageSize: PAGE_SIZE, showSizeChanger: false,
            onChange: (value) => { stopAudio(); setPage(value) } }} />
      </Card>
    </Space>
    <Modal title="上传音频样本" width={760} open={Boolean(uploadingRow)} onCancel={() => resetUploadEditor(false)}
      footer={null} destroyOnHidden>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Steps current={uploadStep} items={[{ title: '选择音频' }, { title: '试听与编辑' }]} />
        {uploadStep === 0 ? <Space direction="vertical" style={{ width: '100%' }}>
          <Typography.Title level={4}>步骤 1 选择音频</Typography.Title>
          <Typography.Text>仅支持 MP3 或 WAV，最大 10MB。提交时长须为 8–60 秒。</Typography.Text>
          <input aria-label="音频文件" type="file" accept=".mp3,.wav,audio/mpeg,audio/wav"
            onChange={(event) => void selectUploadFile(event.target.files?.[0] ?? null)} />
          <Button onClick={() => resetUploadEditor(false)}>取消</Button>
        </Space> : <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Title level={4}>步骤 2 音频试听与编辑</Typography.Title>
          <Typography.Text>拖动波形选择片段，可裁剪后试听。清晰连续的人声更适合训练。</Typography.Text>
          <div style={{ position: 'relative', width: '100%' }}>
            <canvas ref={canvasRef} aria-label="音频波形" style={{ width: '100%', height: 160, cursor: 'crosshair', borderRadius: 8 }}
              onMouseDown={(event) => { const ratio = selectionRatio(event); setSelectionStart(ratio); setSelectionEnd(ratio); setSelecting(true) }}
              onMouseMove={(event) => { if (selecting) setSelectionEnd(selectionRatio(event)) }}
              onMouseUp={() => setSelecting(false)} onMouseLeave={() => setSelecting(false)} />
            {selectionStart !== null && selectionEnd !== null && <div aria-hidden="true" style={{
              position: 'absolute', top: 0, bottom: 0, pointerEvents: 'none', background: 'rgba(22,119,255,.2)',
              left: `${Math.min(selectionStart, selectionEnd) * 100}%`, width: `${Math.abs(selectionEnd - selectionStart) * 100}%`,
            }} />}
          </div>
          <Typography.Text>
            {selectionStart !== null && selectionEnd !== null
              ? `已选 ${(Math.abs(selectionEnd - selectionStart) * (audioBuffer?.duration ?? 0)).toFixed(1)} 秒`
              : `当前时长 ${(audioBuffer?.duration ?? 0).toFixed(1)} 秒`}
          </Typography.Text>
          <audio ref={editorAudioRef} src={editorUrl} onEnded={() => setEditorPlaying(false)} hidden />
          <Space wrap>
            <Button aria-label="试听样本" icon={<SoundOutlined />} onClick={() => void toggleEditorPreview()}>{editorPlaying ? '停止试听' : '试听样本'}</Button>
            <Button aria-label="裁剪所选" icon={<ScissorOutlined />} disabled={selectionStart === null || selectionEnd === null}
              onClick={trimSelection}>裁剪所选</Button>
            <Button icon={<ReloadOutlined />} onClick={resetTrim}>恢复原始</Button>
          </Space>
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <Button aria-label="返回重选" onClick={() => resetUploadEditor()}>返回重选</Button>
            <Button type="primary" loading={uploading} aria-label="上传样本" onClick={() => void upload()}>上传样本</Button>
          </Space>
        </Space>}
      </Space>
    </Modal>
    <Modal title="编辑音色名称" open={Boolean(editingRow)} onCancel={() => { nameSession.current += 1; setEditingRow(null); nameForm.resetFields(); setSavingName(false) }}
      onOk={() => void saveName()} okText="保存" cancelText="取消" confirmLoading={savingName} destroyOnHidden>
      <Form form={nameForm} layout="vertical">
        <Form.Item name="name" label="音色名称" rules={[{ required: true, whitespace: true, message: '请输入音色名称' }]}>
          <Input maxLength={64} />
        </Form.Item>
      </Form>
    </Modal>
  </section>
}

export function VoiceClonePage() {
  return <PageContainer title={<h1 className="page-container-title">音色克隆</h1>}><VoiceClonePanel /></PageContainer>
}
