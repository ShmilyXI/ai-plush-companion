import { ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components'
import { useCallback, useEffect, useRef, useState } from 'react'
import { listAudit, type AuditRow } from '../../api/admin'
import { AdminPage } from './AdminPage'

export function AuditLogPage() {
  const [error, setError] = useState('')
  const actionRef = useRef<ActionType>(null), controllerRef = useRef<AbortController | null>(null), sequence = useRef(0), mounted = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; sequence.current += 1; controllerRef.current?.abort() } }, [])
  const request = useCallback(async (params: { current?: number; pageSize?: number; keyword?: string }) => {
    const requestId = ++sequence.current; controllerRef.current?.abort(); const controller = new AbortController(); controllerRef.current = controller; setError('')
    try { const result = await listAudit(params.keyword?.trim() || '', params.current ?? 1, params.pageSize ?? 20, { signal: controller.signal }); if (!mounted.current || controller.signal.aborted || sequence.current !== requestId) return { data: [], total: 0, success: false }; return { data: result.list, total: result.total, success: true } }
    catch (reason) { if (mounted.current && !controller.signal.aborted && sequence.current === requestId) setError(reason instanceof Error ? reason.message : '审计日志加载失败'); return { data: [], total: 0, success: false } }
  }, [])
  const columns: ProColumns<AuditRow>[] = [
    { title: '关键词', dataIndex: 'keyword', hideInTable: true },
    { title: '时间', dataIndex: 'createdAt', hideInSearch: true }, { title: '操作人', dataIndex: 'operatorId', hideInSearch: true }, { title: '动作', dataIndex: 'action', hideInSearch: true }, { title: '目标', hideInSearch: true, render: (_, row) => `${row.resourceType}/${row.resourceId || '-'}` }, { title: '目标用户', hideInSearch: true, render: (_, row) => row.targetUserId ?? '-' }, { title: '安全摘要', dataIndex: 'summary', ellipsis: true, hideInSearch: true },
  ]
  return <AdminPage title="审计日志" error={error} onRetry={() => void actionRef.current?.reload()}><ProTable<AuditRow> actionRef={actionRef} rowKey="id" columns={columns} request={request} scroll={{ x: 860 }} pagination={{ defaultPageSize: 20 }} options={false} search={{ labelWidth: 'auto' }} /></AdminPage>
}
