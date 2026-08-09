import { message, Table, Tag } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { changeUserStatus, listUsers, type AdminUser } from '../../api/admin'
import { AdminPage, ConfirmButton } from './AdminPage'

export function UserManagementPage() {
  const [rows, setRows] = useState<AdminUser[]>([]), [query, setQuery] = useState(''), [page, setPage] = useState(1), [total, setTotal] = useState(0), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const load = useCallback((signal?: AbortSignal) => { setLoading(true); setError(''); return listUsers(query, page, 20, { signal }).then((data) => { setRows(data.list); setTotal(data.total) }).catch((reason) => { if (!signal?.aborted) setError(reason instanceof Error ? reason.message : '用户加载失败') }).finally(() => { if (!signal?.aborted) setLoading(false) }) }, [page, query])
  useEffect(() => { const controller = new AbortController(); void load(controller.signal); return () => controller.abort() }, [load])
  return <AdminPage title="用户管理" loading={loading} error={error} onSearch={(value) => { setQuery(value.trim()); setPage(1) }}><Table rowKey="id" dataSource={rows} scroll={{ x: 680 }} pagination={{ current: page, pageSize: 20, total, showSizeChanger: false, onChange: setPage }} columns={[
    { title: '用户', render: (_, row) => row.username || row.mobile || row.id }, { title: '手机号', dataIndex: 'mobile' }, { title: '设备数', dataIndex: 'deviceCount' },
    { title: '状态', render: (_, row) => <Tag color={row.status === 1 ? 'green' : 'default'}>{row.status === 1 ? '启用' : '停用'}</Tag> },
    { title: '操作', render: (_, row) => <ConfirmButton label={row.status === 1 ? '停用' : '启用'} title={`确认${row.status === 1 ? '停用' : '启用'}该用户？`} danger={row.status === 1} onConfirm={async () => { await changeUserStatus(row.id, row.status === 1 ? 0 : 1); message.success('状态已更新'); await load() }} /> },
  ]} /></AdminPage>
}
