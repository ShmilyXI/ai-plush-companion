import { DeleteOutlined, EditOutlined } from '@ant-design/icons'
import { Alert, Button, Empty, Form, Input, List, Modal, Space, Spin, Typography, message } from 'antd'
import { useEffect, useRef, useState } from 'react'

import {
  clearAdminMemories,
  deleteAdminMemory,
  listAdminMemories,
  updateAdminMemory,
  type AdminDevice,
} from '../../api/admin'
import type { CompanionMemory } from '../../api/memories'

interface Props {
  device: AdminDevice | null
  onClose: () => void
}

export function AdminDeviceMemoryModal({ device, onClose }: Props) {
  const [memories, setMemories] = useState<CompanionMemory[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<CompanionMemory | null>(null)
  const [deleting, setDeleting] = useState<CompanionMemory | null>(null)
  const [clearOpen, setClearOpen] = useState(false)
  const [mutating, setMutating] = useState(false)
  const [form] = Form.useForm<{ content: string }>()
  const [messageApi, context] = message.useMessage()
  const mutationController = useRef<AbortController | null>(null)

  useEffect(() => {
    if (!device) {
      setMemories([])
      setError('')
      return
    }
    const controller = new AbortController()
    setLoading(true)
    setError('')
    void listAdminMemories(device.id, { signal: controller.signal }).then((items) => {
      if (!controller.signal.aborted) setMemories(items)
    }).catch((reason) => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '记忆加载失败')
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false)
    })
    return () => controller.abort()
  }, [device])

  useEffect(() => () => mutationController.current?.abort(), [])

  function beginEdit(memory: CompanionMemory) {
    setEditing(memory)
    form.setFieldsValue({ content: memory.content })
  }

  async function mutate(action: (signal: AbortSignal) => Promise<void>, success: () => void) {
    mutationController.current?.abort()
    const controller = new AbortController()
    mutationController.current = controller
    setMutating(true)
    try {
      await action(controller.signal)
      if (!controller.signal.aborted) success()
    } catch (reason) {
      if (!controller.signal.aborted) messageApi.error(reason instanceof Error ? reason.message : '操作失败')
    } finally {
      if (!controller.signal.aborted) setMutating(false)
    }
  }

  function saveCorrection({ content }: { content: string }) {
    if (!device || !editing) return
    const target = editing
    void mutate((signal) => updateAdminMemory(device.id, target.id, content, { signal }), () => {
      setMemories((items) => items.map((item) => item.id === target.id ? { ...item, content } : item))
      setEditing(null)
      messageApi.success('记忆已纠正')
    })
  }

  function confirmDelete() {
    if (!device || !deleting) return
    const target = deleting
    void mutate((signal) => deleteAdminMemory(device.id, target.id, { signal }), () => {
      setMemories((items) => items.filter((item) => item.id !== target.id))
      setDeleting(null)
      messageApi.success('记忆已删除')
    })
  }

  function confirmClear() {
    if (!device) return
    void mutate((signal) => clearAdminMemories(device.id, { signal }), () => {
      setMemories([])
      setClearOpen(false)
      messageApi.success('设备记忆已清空')
    })
  }

  return <>{context}<Modal
    title={`${device?.alias?.trim() || device?.id || '设备'}的记忆`}
    open={Boolean(device)}
    width={760}
    footer={<Button onClick={onClose}>关闭</Button>}
    onCancel={onClose}
    destroyOnHidden
  >
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Text type="secondary">所属用户 {device?.bindUserName || '未显示'}</Typography.Text>
      <Button danger icon={<DeleteOutlined />} aria-label="清空绑定角色记忆" disabled={!memories.length} onClick={() => setClearOpen(true)}>清空绑定角色记忆</Button>
      {error && <Alert type="error" showIcon message={error} />}
      <Spin spinning={loading}>
        <List
          dataSource={memories}
          locale={{ emptyText: <Empty description="当前设备还没有长期记忆" /> }}
          renderItem={(memory) => <List.Item actions={[
            <Button key="edit" type="text" icon={<EditOutlined />} aria-label="纠正记忆" onClick={() => beginEdit(memory)} />,
            <Button key="delete" type="text" danger icon={<DeleteOutlined />} aria-label="删除记忆" onClick={() => setDeleting(memory)} />,
          ]}><List.Item.Meta
              title={memory.content}
              description={`${memory.sourceDeviceName?.trim() || '未知设备'} · ${memory.sourceProfileName?.trim() || '未知角色'} · ${new Date(memory.updatedAt).toLocaleString('zh-CN')}`}
            /></List.Item>}
        />
      </Spin>
    </Space>
  </Modal>
  <Modal title="纠正记忆" open={Boolean(editing)} confirmLoading={mutating} okText="保存纠正" cancelText="取消" onCancel={() => setEditing(null)} onOk={() => form.submit()} destroyOnHidden>
    <Form form={form} layout="vertical" onFinish={saveCorrection}><Form.Item label="记忆内容" name="content" rules={[{ required: true, whitespace: true, message: '请输入记忆内容' }, { max: 4000 }]}><Input.TextArea rows={6} maxLength={4000} /></Form.Item></Form>
  </Modal>
  <Modal title="删除记忆" open={Boolean(deleting)} confirmLoading={mutating} okText="确认删除" cancelText="取消" onCancel={() => setDeleting(null)} onOk={confirmDelete}>
    <Typography.Paragraph>删除成功后无法从管理台恢复。</Typography.Paragraph>
  </Modal>
  <Modal title="清空绑定角色记忆" open={clearOpen} confirmLoading={mutating} okText="确认清空" okButtonProps={{ danger: true }} cancelText="取消" onCancel={() => setClearOpen(false)} onOk={confirmClear}>
    <Typography.Paragraph>会清空该用户在当前绑定角色下的长期记忆，包含其他设备共享的内容。</Typography.Paragraph>
  </Modal></>
}
