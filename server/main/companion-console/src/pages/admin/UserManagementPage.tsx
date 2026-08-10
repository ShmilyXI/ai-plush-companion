import { ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components'
import { message, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { changeUserStatus, listUsers, type AdminUser } from '../../api/admin'
import { AdminPage, ConfirmButton } from './AdminPage'

export function UserManagementPage() {
  const [error, setError] = useState('')
  const actionRef = useRef<ActionType>(null), controllerRef = useRef<AbortController | null>(null), sequence = useRef(0), mounted = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; sequence.current += 1; controllerRef.current?.abort() } }, [])
  const request = useCallback(async (params: { current?: number; pageSize?: number; keyword?: string }) => {
    const requestId = ++sequence.current
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    setError('')
    try {
      const result = await listUsers(params.keyword?.trim() || '', params.current ?? 1, params.pageSize ?? 20, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || sequence.current !== requestId) return { data: [], total: 0, success: false }
      return { data: result.list, total: result.total, success: true }
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && sequence.current === requestId) setError(reason instanceof Error ? reason.message : '用户加载失败')
      return { data: [], total: 0, success: false }
    }
  }, [])
  const columns: ProColumns<AdminUser>[] = [
    { title: '关键词', dataIndex: 'keyword', hideInTable: true },
    { title: '用户', hideInSearch: true, render: (_, row) => row.username || row.mobile || row.id }, { title: '手机号', dataIndex: 'mobile', hideInSearch: true }, { title: '设备数', dataIndex: 'deviceCount', hideInSearch: true },
    { title: '状态', hideInSearch: true, render: (_, row) => <Tag color={row.status === 1 ? 'green' : 'default'}>{row.status === 1 ? '启用' : '停用'}</Tag> },
    { title: '操作', valueType: 'option', render: (_, row) => <ConfirmButton label={row.status === 1 ? '停用' : '启用'} title={`确认${row.status === 1 ? '停用' : '启用'}该用户？`} danger={row.status === 1} onConfirm={async () => { await changeUserStatus(row.id, row.status === 1 ? 0 : 1); message.success('状态已更新'); await actionRef.current?.reload() }} /> },
  ]
  return <AdminPage title="用户管理" error={error} onRetry={() => void actionRef.current?.reload()}><ProTable<AdminUser> actionRef={actionRef} rowKey="id" columns={columns} request={request} scroll={{ x: 680 }} pagination={{ defaultPageSize: 20 }} options={false} search={{ labelWidth: 'auto' }} /></AdminPage>
}
