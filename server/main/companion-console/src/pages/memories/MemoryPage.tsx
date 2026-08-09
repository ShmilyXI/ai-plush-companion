import { DeleteOutlined, EditOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Empty, Form, Input, List, Modal, Select, Space, Spin, Typography, message } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'

import { listDevices, type CompanionDevice } from '../../api/devices'
import { clearMemories, deleteMemory, listMemories, updateMemory, type CompanionMemory } from '../../api/memories'

function deviceName(device: CompanionDevice) { return device.alias?.trim() || device.macAddress }

export function MemoryPage() {
  const [devices, setDevices] = useState<CompanionDevice[]>([])
  const [deviceId, setDeviceId] = useState('')
  const [memories, setMemories] = useState<CompanionMemory[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<CompanionMemory | null>(null)
  const [deleting, setDeleting] = useState<CompanionMemory | null>(null)
  const [clearOpen, setClearOpen] = useState(false)
  const [mutating, setMutating] = useState(false)
  const [form] = Form.useForm<{ content: string }>()
  const [messageApi, context] = message.useMessage()
  const mounted = useRef(false)
  const loadSequence = useRef(0)
  const mutationSequence = useRef(0)
  const loadController = useRef<AbortController | null>(null)
  const mutationController = useRef<AbortController | null>(null)

  useEffect(() => {
    mounted.current = true
    const controller = new AbortController()
    loadController.current = controller
    void listDevices({ signal: controller.signal }).then((deviceList) => {
      if (!mounted.current || controller.signal.aborted) return
      setDevices(deviceList)
      setDeviceId(deviceList[0]?.id ?? '')
      if (!deviceList.length) setLoading(false)
    }).catch((reason) => {
      if (mounted.current && !controller.signal.aborted) { setError(reason instanceof Error ? reason.message : '设备加载失败'); setLoading(false) }
    })
    return () => {
      mounted.current = false; loadSequence.current += 1; mutationSequence.current += 1
      loadController.current?.abort(); mutationController.current?.abort()
    }
  }, [])

  const load = useCallback(async (selectedDeviceId: string) => {
    if (!selectedDeviceId) return
    const sequence = ++loadSequence.current
    loadController.current?.abort()
    const controller = new AbortController()
    loadController.current = controller
    setLoading(true); setError('')
    try {
      const data = await listMemories(selectedDeviceId, { signal: controller.signal })
      if (mounted.current && !controller.signal.aborted && loadSequence.current === sequence) setMemories(data)
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && loadSequence.current === sequence) setError(reason instanceof Error ? reason.message : '记忆加载失败')
    } finally { if (mounted.current && !controller.signal.aborted && loadSequence.current === sequence) setLoading(false) }
  }, [])

  useEffect(() => { if (deviceId) void load(deviceId) }, [deviceId, load])

  function beginEdit(memory: CompanionMemory) { setEditing(memory); form.setFieldsValue({ content: memory.content }) }

  function changeDevice(nextDeviceId: string) {
    mutationSequence.current += 1
    mutationController.current?.abort()
    setEditing(null); setDeleting(null); setClearOpen(false); setMutating(false)
    setDeviceId(nextDeviceId)
  }

  async function mutate(action: (controller: AbortController) => Promise<void>, onSuccess: () => void) {
    const sequence = ++mutationSequence.current
    mutationController.current?.abort()
    const controller = new AbortController()
    mutationController.current = controller
    setMutating(true)
    try {
      await action(controller)
      if (!mounted.current || controller.signal.aborted || mutationSequence.current !== sequence) return
      onSuccess()
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && mutationSequence.current === sequence) messageApi.error(reason instanceof Error ? reason.message : '操作失败')
    } finally { if (mounted.current && mutationSequence.current === sequence) setMutating(false) }
  }

  function saveCorrection({ content }: { content: string }) {
    if (!editing) return
    const target = editing
    void mutate((controller) => updateMemory(deviceId, target.id, content, { signal: controller.signal }), () => {
      setMemories((items) => items.map((item) => item.id === target.id ? { ...item, content } : item))
      setEditing(null); messageApi.success('记忆已纠正')
    })
  }

  function confirmDelete() {
    if (!deleting) return
    const target = deleting
    void mutate((controller) => deleteMemory(deviceId, target.id, { signal: controller.signal }), () => {
      setMemories((items) => items.filter((item) => item.id !== target.id))
      setDeleting(null); messageApi.success('记忆已删除')
    })
  }

  function confirmClear() {
    void mutate((controller) => clearMemories(deviceId, { signal: controller.signal }), () => {
      setMemories([]); setClearOpen(false); messageApi.success('该设备的记忆已清空')
    })
  }

  return <section className="console-page memory-page">{context}
    <div className="page-heading"><div><Typography.Title level={1}>记忆</Typography.Title><Typography.Paragraph>这些内容属于你。可以随时查看、纠正或删除。</Typography.Paragraph></div><Button danger icon={<DeleteOutlined />} disabled={!memories.length} onClick={() => setClearOpen(true)}>清空当前设备</Button></div>
    <Alert className="memory-control-note" type="info" showIcon icon={<SafetyCertificateOutlined />} message="只有操作成功后，记忆才会从这里移除。服务暂时不可用时，原内容会保留。" />
    <Card className="surface-card memory-device-card"><Space direction="vertical"><Typography.Text strong>查看设备</Typography.Text><Select aria-label="查看设备" value={deviceId || undefined} placeholder="还没有设备" options={devices.map((device) => ({ label: deviceName(device), value: device.id }))} onChange={changeDevice} /></Space></Card>
    {error && <Alert type="error" showIcon message={error} action={<Button size="small" onClick={() => void load(deviceId)}>重试</Button>} />}
    <Spin spinning={loading}><Card className="surface-card"><List dataSource={memories} locale={{ emptyText: <Empty description={devices.length ? '当前设备还没有长期记忆' : '请先绑定设备'} /> }} renderItem={(memory) => <List.Item actions={[<Button key="edit" type="text" icon={<EditOutlined />} aria-label="纠正这条记忆" onClick={() => beginEdit(memory)} />, <Button key="delete" type="text" danger icon={<DeleteOutlined />} aria-label="删除这条记忆" onClick={() => setDeleting(memory)} />]}><List.Item.Meta title={memory.content} description={<Space split="·"><span>{`${memory.sourceDeviceName?.trim() || '未知设备'} · ${memory.sourceProfileName?.trim() || '未知角色'}`}</span><span>{new Date(memory.updatedAt).toLocaleString('zh-CN')}</span></Space>} /></List.Item>} /></Card></Spin>
    <Modal title="纠正记忆" open={Boolean(editing)} confirmLoading={mutating} okText="保存纠正" cancelText="取消" onCancel={() => setEditing(null)} onOk={() => form.submit()} destroyOnHidden><Form form={form} layout="vertical" onFinish={saveCorrection}><Form.Item label="记忆内容" name="content" rules={[{ required: true, whitespace: true, message: '请输入记忆内容' }, { max: 4000 }]}><Input.TextArea rows={6} maxLength={4000} showCount /></Form.Item></Form></Modal>
    <Modal title="删除这条记忆" open={Boolean(deleting)} confirmLoading={mutating} okText="确认删除" cancelText="取消" onCancel={() => setDeleting(null)} onOk={confirmDelete}><Typography.Paragraph>删除成功后无法从管理台恢复。</Typography.Paragraph></Modal>
    <Modal title="清空当前设备的记忆" open={clearOpen} confirmLoading={mutating} okText="确认清空" okButtonProps={{ danger: true }} cancelText="取消" onCancel={() => setClearOpen(false)} onOk={confirmClear}><Typography.Paragraph>只清空当前所选设备的长期记忆。</Typography.Paragraph></Modal>
  </section>
}
