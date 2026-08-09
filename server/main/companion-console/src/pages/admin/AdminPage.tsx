import { Alert, Button, Card, Input, Modal, Space, Typography } from 'antd'
import type { ReactNode } from 'react'
import { reportAdminError } from './adminErrors'

export function AdminPage({ title, search, onSearch, loading, error, actions, children }: { title: string; search?: string; onSearch?: (value: string) => void; loading?: boolean; error?: string; actions?: ReactNode; children: ReactNode }) {
  return <section className="admin-page"><Space direction="vertical" size="large" style={{ width: '100%' }}>
    <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}><Typography.Title level={2} style={{ margin: 0 }}>{title}</Typography.Title>{actions}</Space>
    {error && <Alert type="error" showIcon message={error} />}
    <Card loading={loading}>{onSearch && <Space.Compact style={{ width: 'min(100%, 420px)', marginBottom: 16 }}><Input.Search defaultValue={search} allowClear enterButton="搜索" onSearch={onSearch} /></Space.Compact>}{children}</Card>
  </Space></section>
}

export function ConfirmButton({ label, title, danger, onConfirm, disabled }: { label: string; title: string; danger?: boolean; disabled?: boolean; onConfirm: () => Promise<void> }) {
  return <Button danger={danger} disabled={disabled} onClick={() => Modal.confirm({ title, okText: '确认', cancelText: '取消', okButtonProps: { danger }, onOk: async () => { try { await onConfirm() } catch (reason) { throw new Error(reportAdminError(reason, '操作失败')) } } })}>{label}</Button>
}
