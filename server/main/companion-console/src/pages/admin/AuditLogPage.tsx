import { Table } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { listAudit, type AuditRow } from '../../api/admin'
import { AdminPage } from './AdminPage'

export function AuditLogPage() {
  const [rows, setRows] = useState<AuditRow[]>([]), [query, setQuery] = useState(''), [page, setPage] = useState(1), [total, setTotal] = useState(0), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const load = useCallback((signal?: AbortSignal) => { setLoading(true); setError(''); return listAudit(query, page, 20, { signal }).then((data) => { setRows(data.list); setTotal(data.total) }).catch((reason) => { if (!signal?.aborted) setError(reason instanceof Error ? reason.message : '审计日志加载失败') }).finally(() => { if (!signal?.aborted) setLoading(false) }) }, [page, query])
  useEffect(() => { const controller = new AbortController(); void load(controller.signal); return () => controller.abort() }, [load])
  return <AdminPage title="审计日志" loading={loading} error={error} onSearch={(value) => { setQuery(value.trim()); setPage(1) }}><Table rowKey="id" dataSource={rows} scroll={{ x: 860 }} pagination={{ current: page, pageSize: 20, total, showSizeChanger: false, onChange: setPage }} columns={[
    { title: '时间', dataIndex: 'createdAt' }, { title: '操作人', dataIndex: 'operatorId' }, { title: '动作', dataIndex: 'action' }, { title: '目标', render: (_, row) => `${row.resourceType}/${row.resourceId || '-'}` }, { title: '目标用户', render: (_, row) => row.targetUserId ?? '-' }, { title: '安全摘要', dataIndex: 'summary', ellipsis: true },
  ]} /></AdminPage>
}
