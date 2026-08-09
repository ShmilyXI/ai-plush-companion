import { DeleteOutlined, EditOutlined, PlusOutlined, SoundOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { createTimbre, deleteTimbres, listTimbres, updateTimbre, type Timbre, type TimbreInput } from '../../api/timbres'
import { listModelNames, type ModelBasicInfo } from '../../api/xiaozhiModels'

const PAGE_SIZE = 20

interface EditorValues {
  ttsModelId: string
  name: string
  ttsVoice: string
  languages: string
  sort: number
  voiceDemo?: string
  remark?: string
  referenceAudio?: string
  referenceText?: string
}

function errorText(reason: unknown, fallback: string) {
  const value = reason instanceof Error ? reason.message.trim() : ''
  return value && value.length <= 160 ? value : fallback
}

function optionalValue(value: string | undefined) {
  return value?.trim() || undefined
}

function isHttpUrl(value: string) {
  try {
    const url = new URL(value)
    return (url.protocol === 'http:' || url.protocol === 'https:') && Boolean(url.hostname)
  } catch {
    return false
  }
}

export function TimbreManagementPage() {
  const [searchParams] = useSearchParams()
  const queryModelId = searchParams.get('ttsModelId')?.trim() ?? ''
  const [models, setModels] = useState<ModelBasicInfo[]>([])
  const [ttsModelId, setTtsModelId] = useState(queryModelId)
  const [name, setName] = useState('')
  const [page, setPage] = useState(1)
  const [rows, setRows] = useState<Timbre[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [playingId, setPlayingId] = useState<string | null>(null)
  const [editing, setEditing] = useState<Timbre | null | undefined>(undefined)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<EditorValues>()
  const [messageApi, messageContext] = message.useMessage()
  const mounted = useRef(false)
  const listController = useRef<AbortController | null>(null)
  const requestSequence = useRef(0)
  const viewSequence = useRef(0)
  const editorSession = useRef(0)
  const viewRef = useRef({ ttsModelId, name, page })
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const previewSequence = useRef(0)
  viewRef.current = { ttsModelId, name, page }

  const load = useCallback(async (modelId: string, nextName: string, nextPage: number) => {
    if (!modelId) {
      setRows([])
      setTotal(0)
      setLoading(false)
      return
    }
    const sequence = ++requestSequence.current
    listController.current?.abort()
    const controller = new AbortController()
    listController.current = controller
    setLoading(true)
    setError('')
    try {
      const result = await listTimbres(
        { ttsModelId: modelId, page: nextPage, limit: PAGE_SIZE, name: nextName },
        { signal: controller.signal },
      )
      if (mounted.current && requestSequence.current === sequence && !controller.signal.aborted) {
        setRows(result.list)
        setTotal(result.total)
      }
    } catch (reason) {
      if (mounted.current && requestSequence.current === sequence && !controller.signal.aborted) {
        setError(errorText(reason, '音色列表加载失败'))
      }
    } finally {
      if (mounted.current && requestSequence.current === sequence && !controller.signal.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    const controller = new AbortController()
    listModelNames('TTS', undefined, { signal: controller.signal }).then((items) => {
      if (!mounted.current || controller.signal.aborted) return
      setModels(items)
      setTtsModelId((current) => current || items[0]?.id || '')
    }).catch((reason) => {
      if (mounted.current && !controller.signal.aborted) setError(errorText(reason, 'TTS 模型加载失败'))
    })
    return () => {
      mounted.current = false
      requestSequence.current += 1
      controller.abort()
      listController.current?.abort()
      editorSession.current += 1
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
    }
  }, [])

  useEffect(() => {
    if (!queryModelId || queryModelId === viewRef.current.ttsModelId) return
    viewSequence.current += 1
    setTtsModelId(queryModelId)
    setPage(1)
  }, [queryModelId])

  useEffect(() => {
    if (ttsModelId) void load(ttsModelId, name, page)
  }, [load, name, page, ttsModelId])

  function openEditor(row: Timbre | null) {
    editorSession.current += 1
    setEditing(row)
    form.setFieldsValue(row ? {
      ttsModelId: row.ttsModelId,
      name: row.name,
      ttsVoice: row.ttsVoice,
      languages: row.languages ?? '',
      sort: row.sort ?? 0,
      voiceDemo: row.voiceDemo ?? '',
      remark: row.remark ?? '',
      referenceAudio: '',
      referenceText: '',
    } : {
      ttsModelId,
      name: '',
      ttsVoice: '',
      languages: 'zh-CN',
      sort: 0,
      voiceDemo: '',
      remark: '',
      referenceAudio: '',
      referenceText: '',
    })
  }

  function closeEditor() {
    editorSession.current += 1
    setEditing(undefined)
    setSaving(false)
    form.resetFields()
  }

  async function save() {
    const session = editorSession.current
    const mutationView = viewSequence.current
    const editingRow = editing
    const values = await form.validateFields()
    if (editorSession.current !== session) return
    const input: TimbreInput = {
      ttsModelId: values.ttsModelId,
      name: values.name.trim(),
      ttsVoice: values.ttsVoice.trim(),
      languages: values.languages.trim(),
      sort: values.sort,
      voiceDemo: optionalValue(values.voiceDemo),
      remark: optionalValue(values.remark),
      referenceAudio: optionalValue(values.referenceAudio),
      referenceText: optionalValue(values.referenceText),
    }
    setSaving(true)
    setError('')
    try {
      if (editingRow) await updateTimbre(editingRow.id, input)
      else await createTimbre(input)
      if (!mounted.current || editorSession.current !== session) return
      messageApi.success(editingRow ? '音色已更新' : '音色已创建')
      closeEditor()
      const current = viewRef.current
      if (viewSequence.current === mutationView && input.ttsModelId === current.ttsModelId) {
        await load(current.ttsModelId, current.name, current.page)
      }
    } catch (reason) {
      if (mounted.current && editorSession.current === session) setError(errorText(reason, '音色保存失败'))
    } finally {
      if (mounted.current && editorSession.current === session) setSaving(false)
    }
  }

  async function remove(row: Timbre) {
    const mutationView = viewSequence.current
    setError('')
    try {
      await deleteTimbres([row.id])
      if (!mounted.current) return
      messageApi.success('音色已删除')
      if (viewSequence.current !== mutationView) return
      const current = viewRef.current
      const nextPage = rows.length === 1 && current.page > 1 ? current.page - 1 : current.page
      if (nextPage !== current.page) {
        viewSequence.current += 1
        setPage(nextPage)
      } else {
        await load(current.ttsModelId, current.name, current.page)
      }
    } catch (reason) {
      if (mounted.current && viewSequence.current === mutationView) setError(errorText(reason, '音色删除失败'))
    }
  }

  async function preview(row: Timbre) {
    if (!row.voiceDemo) return
    if (playingId === row.id) {
      stopPreview()
      return
    }
    const sequence = ++previewSequence.current
    const current = audioRef.current
    if (current) {
      current.onerror = null
      current.onended = null
      current.pause()
      current.currentTime = 0
      current.src = ''
    }
    setPlayingId(null)
    if (!isHttpUrl(row.voiceDemo)) {
      setError('试听失败')
      return
    }
    const audio = current ?? new Audio()
    audioRef.current = audio
    audio.onerror = () => {
      if (mounted.current && previewSequence.current === sequence) {
        setPlayingId(null)
        setError('试听失败')
      }
    }
    audio.onended = () => {
      if (mounted.current && previewSequence.current === sequence) setPlayingId(null)
    }
    audio.src = row.voiceDemo
    setError('')
    try {
      await audio.play()
      if (mounted.current && previewSequence.current === sequence) setPlayingId(row.id)
    } catch {
      if (mounted.current && previewSequence.current === sequence) {
        setPlayingId(null)
        setError('试听失败')
      }
    }
  }

  function stopPreview() {
    previewSequence.current += 1
    const audio = audioRef.current
    if (audio) {
      audio.onerror = null
      audio.onended = null
      audio.pause()
      audio.currentTime = 0
      audio.src = ''
    }
    setPlayingId(null)
  }

  const columns: ColumnsType<Timbre> = [
    { title: '名称', dataIndex: 'name' },
    { title: '语言', dataIndex: 'languages', render: (value: string | null) => value || '未填写' },
    { title: '音色编码', dataIndex: 'ttsVoice' },
    { title: '备注', dataIndex: 'remark', render: (value: string | null) => value || '无' },
    { title: '排序', dataIndex: 'sort', render: (value: number | null) => value ?? 0 },
    {
      title: '操作',
      render: (_, row) => <Space wrap>
        {row.voiceDemo ? <Button
          aria-label={`${playingId === row.id ? '停止' : '试听'}${row.name}`}
          icon={<SoundOutlined />}
          onClick={() => void preview(row)}
        >{playingId === row.id ? '停止' : '试听'}</Button> : <Typography.Text type="secondary">暂无试听样本</Typography.Text>}
        <Button aria-label={`编辑${row.name}`} icon={<EditOutlined />} onClick={() => openEditor(row)}>编辑</Button>
        <Popconfirm title="确认删除该音色？" okText="确认" cancelText="取消" onConfirm={() => remove(row)}>
          <Button aria-label={`删除${row.name}`} danger icon={<DeleteOutlined />}>删除</Button>
        </Popconfirm>
      </Space>,
    },
  ]

  return <section>
    {messageContext}
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
        <Typography.Title level={2} style={{ margin: 0 }}>TTS 音色管理</Typography.Title>
        <Button aria-label="新增音色" type="primary" icon={<PlusOutlined />} disabled={!ttsModelId} onClick={() => openEditor(null)}>新增音色</Button>
      </Space>
      {error && <Alert type="error" showIcon message={error} />}
      <Card>
        <Space wrap style={{ marginBottom: 16 }}>
          <Select aria-label="TTS 模型" value={ttsModelId || undefined} style={{ width: 260 }}
            options={models.map((model) => ({ value: model.id, label: model.modelName }))}
            onChange={(value) => { viewSequence.current += 1; setTtsModelId(value); setPage(1) }} />
          <Input.Search aria-label="音色名称" allowClear enterButton="搜索" placeholder="按音色名称搜索"
            onSearch={(value) => { viewSequence.current += 1; setName(value.trim()); setPage(1) }} />
        </Space>
        <Table rowKey="id" loading={loading} dataSource={rows} columns={columns} scroll={{ x: 820 }}
          pagination={{ current: page, total, pageSize: PAGE_SIZE, showSizeChanger: false,
            onChange: (value) => { viewSequence.current += 1; setPage(value) } }} />
      </Card>
    </Space>
    <Modal title={editing ? '编辑音色' : '新增音色'} open={editing !== undefined}
      onCancel={closeEditor} onOk={() => void save()}
      okText="保存" cancelText="取消" confirmLoading={saving} destroyOnHidden>
      <Form form={form} layout="vertical">
        <Form.Item name="ttsModelId" label="TTS 模型" rules={[{ required: true, message: '请选择 TTS 模型' }]}>
          <Select options={models.map((model) => ({ value: model.id, label: model.modelName }))} />
        </Form.Item>
        <Form.Item name="name" label="音色名称" rules={[{ required: true, whitespace: true, message: '请输入音色名称' }]}><Input /></Form.Item>
        <Form.Item name="ttsVoice" label="音色编码" rules={[{ required: true, whitespace: true, message: '请输入音色编码' }]}><Input /></Form.Item>
        <Form.Item name="languages" label="语言" rules={[{ required: true, whitespace: true, message: '请输入语言' }]}><Input /></Form.Item>
        <Form.Item name="voiceDemo" label="试听地址"><Input /></Form.Item>
        <Form.Item name="referenceAudio" label="参考音频"><Input /></Form.Item>
        <Form.Item name="referenceText" label="参考文本"><Input /></Form.Item>
        <Form.Item name="remark" label="备注"><Input /></Form.Item>
        <Form.Item name="sort" label="排序" rules={[{ required: true, message: '请输入排序' }, { type: 'number', min: 0, message: '排序不能小于 0' }]}>
          <InputNumber min={0} precision={0} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </Modal>
  </section>
}
