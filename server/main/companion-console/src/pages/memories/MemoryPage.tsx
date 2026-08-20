import { CopyOutlined, DeleteOutlined, EditOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { PageContainer } from '@ant-design/pro-components'
import { Alert, Button, Card, Empty, Form, Input, List, Modal, Select, Space, Spin, Typography, message } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'

import { listDevices, type CompanionDevice } from '../../api/devices'
import { clearMemories, deleteMemory, listMemories, listMemoryMigrations, migrateMemories, previewMemoryMigration, retryMemoryMigration, updateMemory, type CompanionMemory, type MemoryMigrationPreview, type MemoryMigrationResult } from '../../api/memories'

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
  const [migrationOpen, setMigrationOpen] = useState(false)
  const [migrationSource, setMigrationSource] = useState('')
  const [migrationTarget, setMigrationTarget] = useState('')
  const [migrationMode, setMigrationMode] = useState<'merge' | 'overwrite'>('merge')
  const [migrationPreview, setMigrationPreview] = useState<MemoryMigrationPreview | null>(null)
  const [migrationError, setMigrationError] = useState('')
  const [migrationPending, setMigrationPending] = useState(false)
  const [migrationHistory, setMigrationHistory] = useState<MemoryMigrationResult[]>([])
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

  function openMigration() {
    const target = devices.find((item) => item.id !== deviceId)?.id ?? ''
    setMigrationSource(deviceId); setMigrationTarget(target); setMigrationPreview(null); setMigrationError(''); setMigrationOpen(true)
    void listMemoryMigrations().then(setMigrationHistory).catch(() => setMigrationHistory([]))
  }

  async function previewMigration() {
    if (!migrationSource || !migrationTarget || migrationSource === migrationTarget) return
    setMigrationPending(true); setMigrationError('')
    try { setMigrationPreview(await previewMemoryMigration(migrationSource, migrationTarget)) }
    catch (reason) { setMigrationError(reason instanceof Error ? reason.message : '迁移预览失败') }
    finally { setMigrationPending(false) }
  }

  async function confirmMigration() {
    if (!migrationPreview) return
    setMigrationPending(true); setMigrationError('')
    try {
      const result = await migrateMemories(migrationSource, migrationTarget, migrationMode)
      if (result.outcome !== 'SUCCEEDED') throw new Error('迁移未完成')
      messageApi.success(`迁移完成，导入 ${result.importedCount} 条，跳过 ${result.skippedCount} 条`)
      setMigrationOpen(false)
      setMigrationHistory((items) => [result, ...items])
      if (migrationTarget === deviceId) void load(deviceId)
    } catch (reason) { setMigrationError(reason instanceof Error ? reason.message : '迁移失败') }
    finally { setMigrationPending(false) }
  }

  async function retryMigration(item: MemoryMigrationResult) {
    setMigrationPending(true); setMigrationError('')
    try {
      const result = await retryMemoryMigration(item.id)
      setMigrationHistory((items) => [result, ...items.filter((current) => current.id !== item.id)])
      messageApi.success('迁移已重试')
    } catch (reason) { setMigrationError(reason instanceof Error ? reason.message : '迁移重试失败') }
    finally { setMigrationPending(false) }
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
      setMemories([]); setClearOpen(false); messageApi.success('该角色的长期记忆已清空')
    })
  }

  return <PageContainer className="console-page memory-page" title={<h1 className="page-container-title">记忆</h1>} subTitle="这些内容属于你。可以随时查看、纠正或删除。" extra={<Space><Button icon={<CopyOutlined />} aria-label="迁移整库记忆" disabled={devices.length < 2} onClick={openMigration}>迁移整库记忆</Button><Button danger icon={<DeleteOutlined />} aria-label="清空当前角色记忆" disabled={!memories.length} onClick={() => setClearOpen(true)}>清空当前角色记忆</Button></Space>}>{context}
    <Alert className="memory-control-note" type="info" showIcon icon={<SafetyCertificateOutlined />} message="只有操作成功后，记忆才会从这里移除。服务暂时不可用时，原内容会保留。" />
    <Card className="surface-card memory-device-card"><Space direction="vertical"><Typography.Text strong>查看设备</Typography.Text><Select aria-label="查看设备" value={deviceId || undefined} placeholder="还没有设备" options={devices.map((device) => ({ label: deviceName(device), value: device.id }))} onChange={changeDevice} /></Space></Card>
    {error && <Alert type="error" showIcon message={error} action={<Button size="small" onClick={() => void load(deviceId)}>重试</Button>} />}
    <Spin spinning={loading}><Card className="surface-card"><List dataSource={memories} locale={{ emptyText: <Empty description={devices.length ? '当前设备还没有长期记忆' : '请先绑定设备'} /> }} renderItem={(memory) => <List.Item actions={[<Button key="edit" type="text" icon={<EditOutlined />} aria-label="纠正这条记忆" onClick={() => beginEdit(memory)} />, <Button key="delete" type="text" danger icon={<DeleteOutlined />} aria-label="删除这条记忆" onClick={() => setDeleting(memory)} />]}><List.Item.Meta title={memory.content} description={<Space split="·"><span>{`${memory.sourceDeviceName?.trim() || '未知设备'} · ${memory.sourceProfileName?.trim() || '未知角色'}`}</span><span>{new Date(memory.updatedAt).toLocaleString('zh-CN')}</span></Space>} /></List.Item>} /></Card></Spin>
    <Modal title="纠正记忆" open={Boolean(editing)} confirmLoading={mutating} okText="保存纠正" cancelText="取消" onCancel={() => setEditing(null)} onOk={() => form.submit()} destroyOnHidden><Form form={form} layout="vertical" onFinish={saveCorrection}><Form.Item label="记忆内容" name="content" rules={[{ required: true, whitespace: true, message: '请输入记忆内容' }, { max: 4000 }]}><Input.TextArea rows={6} maxLength={4000} showCount /></Form.Item></Form></Modal>
    <Modal title="删除这条记忆" open={Boolean(deleting)} confirmLoading={mutating} okText="确认删除" cancelText="取消" onCancel={() => setDeleting(null)} onOk={confirmDelete}><Typography.Paragraph>删除成功后无法从管理台恢复。</Typography.Paragraph></Modal>
    <Modal title="清空当前角色记忆" open={clearOpen} confirmLoading={mutating} okText="确认清空" okButtonProps={{ danger: true }} cancelText="取消" onCancel={() => setClearOpen(false)} onOk={confirmClear}><Typography.Paragraph>会清空该设备当前绑定角色的长期记忆。同一用户使用这个角色的其他设备也会受影响。</Typography.Paragraph></Modal>
    <Modal title="迁移整库记忆" open={migrationOpen} confirmLoading={migrationPending} okText="开始迁移" cancelText="取消" okButtonProps={{ disabled: !migrationPreview }} onCancel={() => setMigrationOpen(false)} onOk={() => void confirmMigration()}>
      {migrationError && <Alert type="error" showIcon message={migrationError} style={{ marginBottom: 16 }} />}
      <Form layout="vertical">
        <Form.Item label="来源设备"><Select aria-label="来源设备" value={migrationSource || undefined} options={devices.map((device) => ({ label: deviceName(device), value: device.id }))} onChange={(value) => { setMigrationSource(value); setMigrationPreview(null) }} /></Form.Item>
        <Form.Item label="目标设备"><Select aria-label="目标设备" value={migrationTarget || undefined} options={devices.filter((device) => device.id !== migrationSource).map((device) => ({ label: deviceName(device), value: device.id }))} onChange={(value) => { setMigrationTarget(value); setMigrationPreview(null) }} /></Form.Item>
        <Form.Item label="迁移模式"><Select aria-label="迁移模式" value={migrationMode} options={[{ label: '合并，保留双方并跳过重复内容', value: 'merge' }, { label: '覆盖，只保留来源库', value: 'overwrite' }]} onChange={(value: 'merge' | 'overwrite') => { setMigrationMode(value); setMigrationPreview(null) }} /></Form.Item>
      </Form>
      {migrationPreview && <Alert type="info" showIcon message={`来源 ${migrationPreview.sourceCount} 条，目标 ${migrationPreview.targetCount} 条，模式为${migrationMode === 'merge' ? '合并' : '覆盖'}`} />}
      <Button style={{ marginTop: 16 }} onClick={() => void previewMigration()} loading={migrationPending} disabled={!migrationSource || !migrationTarget || migrationSource === migrationTarget}>读取迁移预览</Button>
      {migrationHistory.length > 0 && <List size="small" header="最近迁移" dataSource={migrationHistory.slice(0, 5)} renderItem={(item) => <List.Item actions={item.retryable && item.outcome !== 'SUCCEEDED' ? [<Button key="retry" type="link" onClick={() => void retryMigration(item)} loading={migrationPending}>重试</Button>] : undefined}><List.Item.Meta title={`${item.mode === 'merge' ? '合并' : '覆盖'} · ${item.outcome}`} description={`导入 ${item.importedCount} 条，跳过 ${item.skippedCount} 条`} /></List.Item>} />}
    </Modal>
  </PageContainer>
}
