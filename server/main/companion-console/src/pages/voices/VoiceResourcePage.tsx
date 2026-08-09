import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useRef, useState, type Key } from 'react'

import {
  createVoiceResources,
  deleteVoiceResources,
  listAssignableUsers,
  listTtsPlatforms,
  listVoiceResources,
  type AssignableUser,
  type TtsPlatform,
  type VoiceResource,
  type VoiceResourceInput,
} from '../../api/voiceResources'
import { useAuthStore } from '../../auth/authStore'

const PAGE_SIZE = 20

type AllocationValues = VoiceResourceInput

function errorText(reason: unknown, fallback: string) {
  const text = reason instanceof Error ? reason.message.trim() : ''
  return text && text.length <= 160 ? text : fallback
}

function status(row: VoiceResource) {
  if (!row.hasVoice) return { color: 'default', text: '等待上传' }
  if (row.trainStatus === 0) return { color: 'processing', text: '等待训练' }
  if (row.trainStatus === 1) return { color: 'processing', text: '训练中' }
  if (row.trainStatus === 2) return { color: 'success', text: '训练成功' }
  return { color: 'error', text: '训练失败' }
}

export function VoiceResourcePage() {
  const isSuperAdmin = useAuthStore((state) => state.hasPermission('sys:role:superAdmin'))
  const [rows, setRows] = useState<VoiceResource[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [name, setName] = useState('')
  const [loading, setLoading] = useState(isSuperAdmin)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [platforms, setPlatforms] = useState<TtsPlatform[]>([])
  const [users, setUsers] = useState<AssignableUser[]>([])
  const [usersLoading, setUsersLoading] = useState(false)
  const [selectedRowKeys, setSelectedRowKeys] = useState<Key[]>([])
  const [form] = Form.useForm<AllocationValues>()
  const [messageApi, messageContext] = message.useMessage()
  const mounted = useRef(false)
  const listController = useRef<AbortController | null>(null)
  const userController = useRef<AbortController | null>(null)
  const requestSequence = useRef(0)
  const userSequence = useRef(0)
  const editorSession = useRef(0)
  const viewRef = useRef({ page, name })
  viewRef.current = { page, name }

  const load = useCallback(async (nextPage: number, nextName: string) => {
    if (!isSuperAdmin) return
    const sequence = ++requestSequence.current
    listController.current?.abort()
    const controller = new AbortController()
    listController.current = controller
    setLoading(true)
    setError('')
    try {
      const result = await listVoiceResources({
        page: nextPage, limit: PAGE_SIZE, name: nextName, orderField: 'create_date', order: 'desc',
      }, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || sequence !== requestSequence.current) return
      setRows(result.list)
      setTotal(result.total)
      setSelectedRowKeys((current) => current.filter((key) => result.list.some((row) => row.id === key)))
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && sequence === requestSequence.current) {
        setError(errorText(reason, '音色资源加载失败'))
      }
    } finally {
      if (mounted.current && !controller.signal.aborted && sequence === requestSequence.current) setLoading(false)
    }
  }, [isSuperAdmin])

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      requestSequence.current += 1
      userSequence.current += 1
      listController.current?.abort()
      userController.current?.abort()
    }
  }, [])

  useEffect(() => {
    if (isSuperAdmin) void load(page, name)
  }, [isSuperAdmin, load, name, page])

  async function openAllocation() {
    const session = ++editorSession.current
    setOpen(true)
    setError('')
    form.resetFields()
    setUsers([])
    try {
      const result = await listTtsPlatforms()
      if (mounted.current && editorSession.current === session) setPlatforms(result)
    } catch (reason) {
      if (mounted.current && editorSession.current === session) setError(errorText(reason, 'TTS 平台加载失败'))
    }
  }

  function closeAllocation() {
    editorSession.current += 1
    userSequence.current += 1
    userController.current?.abort()
    setOpen(false)
    setSaving(false)
    setUsers([])
    form.resetFields()
  }

  async function searchUsers(query: string) {
    const sequence = ++userSequence.current
    userController.current?.abort()
    if (!query.trim()) {
      setUsers([])
      setUsersLoading(false)
      return
    }
    const controller = new AbortController()
    userController.current = controller
    setUsersLoading(true)
    try {
      const result = await listAssignableUsers(query.trim(), 1, 20, { signal: controller.signal })
      if (mounted.current && !controller.signal.aborted && sequence === userSequence.current) setUsers(result.list)
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && sequence === userSequence.current) {
        setError(errorText(reason, '用户查询失败'))
      }
    } finally {
      if (mounted.current && !controller.signal.aborted && sequence === userSequence.current) setUsersLoading(false)
    }
  }

  async function allocate() {
    const session = editorSession.current
    let values: AllocationValues
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    if (editorSession.current !== session) return
    setSaving(true)
    setError('')
    try {
      await createVoiceResources({
        modelId: values.modelId,
        voiceIds: values.voiceIds.map((value) => value.trim()).filter(Boolean),
        userId: values.userId,
        languages: values.languages.trim(),
      })
      if (!mounted.current || editorSession.current !== session) return
      messageApi.success('音色资源已分配')
      closeAllocation()
      const current = viewRef.current
      await load(current.page, current.name)
    } catch (reason) {
      if (mounted.current && editorSession.current === session) setError(errorText(reason, '音色资源分配失败'))
    } finally {
      if (mounted.current && editorSession.current === session) setSaving(false)
    }
  }

  async function removeIds(ids: string[]) {
    setError('')
    try {
      await deleteVoiceResources(ids)
      if (!mounted.current) return
      messageApi.success(ids.length > 1 ? `已删除 ${ids.length} 条音色资源` : '音色资源已删除')
      setSelectedRowKeys([])
      const current = viewRef.current
      await load(current.page, current.name)
    } catch (reason) {
      if (mounted.current) setError(errorText(reason, '音色资源删除失败'))
    }
  }

  const columns: ColumnsType<VoiceResource> = [
    { title: '音色 ID', dataIndex: 'voiceId' },
    { title: '名称', dataIndex: 'name' },
    { title: '归属账号', dataIndex: 'userName', render: (value: string, row) => value || row.userId },
    { title: '平台', dataIndex: 'modelName' },
    { title: '语言', dataIndex: 'languages', render: (value: string) => value || '未填写' },
    { title: '状态', render: (_, row) => { const current = status(row); return <Tag color={current.color}>{current.text}</Tag> } },
    { title: '创建时间', dataIndex: 'createDate', render: (value: string | null) => value ? new Date(value).toLocaleString() : '未知' },
    {
      title: '操作',
      render: (_, row) => <Popconfirm title="确认删除该音色资源？" okText="确认" cancelText="取消" onConfirm={() => removeIds([row.id])}>
        <Button danger icon={<DeleteOutlined />} aria-label={`删除${row.name}`}>删除</Button>
      </Popconfirm>,
    },
  ]

  if (!isSuperAdmin) return <section>
    <Typography.Title level={2}>音色资源</Typography.Title>
    <Alert type="warning" showIcon message="仅超级管理员可管理音色资源" />
  </section>

  return <section>
    {messageContext}
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
        <Typography.Title level={2} style={{ margin: 0 }}>音色资源</Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} aria-label="分配音色资源" onClick={() => void openAllocation()}>分配资源</Button>
      </Space>
      {error && <Alert type="error" showIcon message={error} />}
      <Card>
        <Space wrap style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }}>
          <Input.Search aria-label="资源名称或音色 ID" allowClear enterButton="搜索" placeholder="按名称或音色 ID 搜索"
            style={{ maxWidth: 360 }} onSearch={(value) => { setSelectedRowKeys([]); setName(value.trim()); setPage(1) }} />
          <Popconfirm title={`确认删除选中的 ${selectedRowKeys.length} 条音色资源？`} okText="确认" cancelText="取消"
            disabled={selectedRowKeys.length === 0} onConfirm={() => removeIds(selectedRowKeys.map(String))}>
            <Button danger icon={<DeleteOutlined />} aria-label="删除所选音色资源" disabled={selectedRowKeys.length === 0}>
              删除所选{selectedRowKeys.length > 0 ? `（${selectedRowKeys.length}）` : ''}
            </Button>
          </Popconfirm>
        </Space>
        <Table rowKey="id" loading={loading} dataSource={rows} columns={columns} scroll={{ x: 980 }}
          rowSelection={{ selectedRowKeys, onChange: setSelectedRowKeys }}
          pagination={{ current: page, pageSize: PAGE_SIZE, total, showSizeChanger: false, onChange: setPage }} />
      </Card>
    </Space>
    <Modal title="分配音色资源" open={open} onCancel={closeAllocation} onOk={() => void allocate()}
      okText="确定" cancelText="取消" confirmLoading={saving} destroyOnHidden>
      <Form form={form} layout="vertical">
        <Form.Item name="modelId" label="TTS 平台" rules={[{ required: true, message: '请选择 TTS 平台' }]}>
          <Select showSearch options={platforms.map((item) => ({ value: item.id, label: item.modelName }))}
            onChange={() => form.setFieldValue('voiceIds', [])} />
        </Form.Item>
        <Form.Item name="voiceIds" label="音色 ID" rules={[{ required: true, message: '请输入音色 ID' }]}>
          <Select mode="tags" tokenSeparators={[',']} open={false} placeholder="输入后按回车，可添加多个" />
        </Form.Item>
        <Form.Item name="userId" label="归属账号" rules={[{ required: true, message: '请选择归属账号' }]}>
          <Select showSearch filterOption={false} onSearch={(value) => void searchUsers(value)} loading={usersLoading}
            options={users.map((item) => ({ value: item.id, label: item.mobile || item.id }))} />
        </Form.Item>
        <Form.Item name="languages" label="语言" rules={[{ required: true, whitespace: true, message: '请输入语言' }]}><Input /></Form.Item>
      </Form>
    </Modal>
  </section>
}
