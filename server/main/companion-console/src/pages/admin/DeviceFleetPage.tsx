import { ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components'
import { Alert, Button, Form, Input, Modal, Space, message } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { listDevices, renameAdminDevice, unbindAdminDevice, type AdminDevice } from '../../api/admin'
import { AdminPage } from './AdminPage'
import { AdminDeviceMemoryModal } from './AdminDeviceMemoryModal'

export function DeviceFleetPage() {
  const [error, setError] = useState('')
  const [memoryDevice, setMemoryDevice] = useState<AdminDevice | null>(null)
  const [editing, setEditing] = useState<AdminDevice | null>(null)
  const [saving, setSaving] = useState(false)
  const [renameError, setRenameError] = useState('')
  const [form] = Form.useForm<{ alias: string }>()
  const actionRef = useRef<ActionType>(null), controllerRef = useRef<AbortController | null>(null), sequence = useRef(0), mounted = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; sequence.current += 1; controllerRef.current?.abort() } }, [])
  const request = useCallback(async (params: { current?: number; pageSize?: number; keyword?: string }) => {
    const requestId = ++sequence.current; controllerRef.current?.abort(); const controller = new AbortController(); controllerRef.current = controller; setError('')
    try { const result = await listDevices(params.keyword?.trim() || '', params.current ?? 1, params.pageSize ?? 20, { signal: controller.signal }); if (!mounted.current || controller.signal.aborted || sequence.current !== requestId) return { data: [], total: 0, success: false }; return { data: result.list, total: result.total, success: true } }
    catch (reason) { if (mounted.current && !controller.signal.aborted && sequence.current === requestId) setError(reason instanceof Error ? reason.message : '设备加载失败'); return { data: [], total: 0, success: false } }
  }, [])

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
      setEditing(null)
      message.success('设备名称已更新')
      await actionRef.current?.reload()
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
          message.success('设备已解绑')
          await actionRef.current?.reload()
        } catch (reason) {
          const text = reason instanceof Error ? reason.message : '解绑失败'
          message.error(text)
          throw reason
        }
      },
    })
  }

  const columns: ProColumns<AdminDevice>[] = [
    { title: '关键词', dataIndex: 'keyword', hideInTable: true },
    { title: '设备', hideInSearch: true, render: (_, row) => row.alias || row.id }, { title: 'MAC', dataIndex: 'macAddress', hideInSearch: true }, { title: '用户', dataIndex: 'bindUserName', hideInSearch: true }, { title: '型号', hideInSearch: true, render: (_, row) => row.deviceType || row.board || '-' }, { title: '版本', dataIndex: 'appVersion', hideInSearch: true },
    { title: '操作', valueType: 'option', render: (_, row) => <Space size={0}><Button type="link" aria-label="管理记忆" onClick={() => setMemoryDevice(row)}>记忆</Button><Button type="link" aria-label="编辑名称" onClick={() => beginRename(row)}>改名</Button><Button type="link" danger aria-label="解绑设备" onClick={() => confirmUnbind(row)}>解绑</Button></Space> },
  ]
  return <><AdminPage title="设备运营" error={error} onRetry={() => void actionRef.current?.reload()}><ProTable<AdminDevice> actionRef={actionRef} rowKey="id" columns={columns} request={request} scroll={{ x: 880 }} pagination={{ defaultPageSize: 20 }} options={false} search={{ labelWidth: 'auto' }} /></AdminPage><AdminDeviceMemoryModal device={memoryDevice} onClose={() => setMemoryDevice(null)} /><Modal title="编辑设备名称" open={Boolean(editing)} confirmLoading={saving} okText="保存名称" cancelText="取消" onCancel={() => setEditing(null)} onOk={() => form.submit()} destroyOnHidden>{renameError && <Alert type="error" showIcon message={renameError} />}<Form form={form} layout="vertical" onFinish={saveRename}><Form.Item label="设备名称" name="alias" rules={[{ required: true, whitespace: true, message: '请输入设备名称' }, { max: 64 }]}><Input maxLength={64} /></Form.Item></Form></Modal></>
}
