import { Alert, Button, Form, Input, Modal, Space, Table, message } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { listDevices, renameAdminDevice, unbindAdminDevice, type AdminDevice } from '../../api/admin'
import { AdminPage } from './AdminPage'
import { AdminDeviceMemoryModal } from './AdminDeviceMemoryModal'

export function DeviceFleetPage() {
  const [rows, setRows] = useState<AdminDevice[]>([]), [query, setQuery] = useState(''), [page, setPage] = useState(1), [total, setTotal] = useState(0), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const [memoryDevice, setMemoryDevice] = useState<AdminDevice | null>(null)
  const [editing, setEditing] = useState<AdminDevice | null>(null)
  const [saving, setSaving] = useState(false)
  const [renameError, setRenameError] = useState('')
  const [form] = Form.useForm<{ alias: string }>()
  const load = useCallback((signal?: AbortSignal) => { setLoading(true); setError(''); return listDevices(query, page, 20, { signal }).then((data) => { setRows(data.list); setTotal(data.total) }).catch((reason) => { if (!signal?.aborted) setError(reason instanceof Error ? reason.message : '设备加载失败') }).finally(() => { if (!signal?.aborted) setLoading(false) }) }, [page, query])
  useEffect(() => { const controller = new AbortController(); void load(controller.signal); return () => controller.abort() }, [load])

  function beginRename(device: AdminDevice) {
    setEditing(device)
    setRenameError('')
    form.setFieldsValue({ alias: device.alias || '' })
  }

  async function saveRename({ alias }: { alias: string }) {
    if (!editing) return
    setSaving(true)
    setRenameError('')
    try {
      await renameAdminDevice(editing.id, alias.trim())
      setRows((items) => items.map((item) => item.id === editing.id ? { ...item, alias: alias.trim() } : item))
      setEditing(null)
      message.success('设备名称已更新')
    } catch (reason) {
      const text = reason instanceof Error ? reason.message : '设备名称更新失败'
      setRenameError(text)
      message.error(text)
    } finally {
      setSaving(false)
    }
  }

  function confirmUnbind(device: AdminDevice) {
    Modal.confirm({
      title: '解绑设备',
      content: `确认解绑${device.alias || device.id}？`,
      okText: '确认解绑',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await unbindAdminDevice(device.id)
          setRows((items) => items.filter((item) => item.id !== device.id))
          setTotal((value) => Math.max(0, value - 1))
          message.success('设备已解绑')
        } catch (reason) {
          const text = reason instanceof Error ? reason.message : '解绑失败'
          message.error(text)
          throw reason
        }
      },
    })
  }

  return <><AdminPage title="设备总览" loading={loading} error={error} onSearch={(value) => { setQuery(value.trim()); setPage(1) }}><Table rowKey="id" dataSource={rows} scroll={{ x: 880 }} pagination={{ current: page, pageSize: 20, total, showSizeChanger: false, onChange: setPage }} columns={[
    { title: '设备', render: (_, row) => row.alias || row.id }, { title: 'MAC', dataIndex: 'macAddress' }, { title: '用户', dataIndex: 'bindUserName' }, { title: '型号', render: (_, row) => row.deviceType || row.board || '-' }, { title: '版本', dataIndex: 'appVersion' },
    { title: '操作', render: (_, row) => <Space size={0}><Button type="link" aria-label="管理记忆" onClick={() => setMemoryDevice(row)}>记忆</Button><Button type="link" aria-label="编辑名称" onClick={() => beginRename(row)}>改名</Button><Button type="link" danger aria-label="解绑设备" onClick={() => confirmUnbind(row)}>解绑</Button></Space> },
  ]} /></AdminPage><AdminDeviceMemoryModal device={memoryDevice} onClose={() => setMemoryDevice(null)} /><Modal title="编辑设备名称" open={Boolean(editing)} confirmLoading={saving} okText="保存名称" cancelText="取消" onCancel={() => setEditing(null)} onOk={() => form.submit()} destroyOnHidden>{renameError && <Alert type="error" showIcon message={renameError} />}<Form form={form} layout="vertical" onFinish={saveRename}><Form.Item label="设备名称" name="alias" rules={[{ required: true, whitespace: true, message: '请输入设备名称' }, { max: 64 }]}><Input maxLength={64} /></Form.Item></Form></Modal></>
}
