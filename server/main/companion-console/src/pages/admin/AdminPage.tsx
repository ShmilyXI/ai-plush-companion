import { PageContainer } from '@ant-design/pro-components'
import { Alert, Button, Modal } from 'antd'
import type { ReactNode } from 'react'
import { reportAdminError } from './adminErrors'

export function AdminPage({ title, subTitle, error, onRetry, actions, children }: { title: string; subTitle?: string; error?: string; onRetry?: () => void; actions?: ReactNode; children: ReactNode }) {
  return <PageContainer className="console-page admin-page" title={<h1 className="page-container-title">{title}</h1>} subTitle={subTitle} extra={actions}>
    {error && <Alert className="admin-page-alert" type="error" showIcon message={error} action={onRetry ? <Button size="small" onClick={onRetry}>重试</Button> : undefined} />}
    {children}
  </PageContainer>
}

export function ConfirmButton({ label, title, danger, onConfirm, disabled }: { label: string; title: string; danger?: boolean; disabled?: boolean; onConfirm: () => Promise<void> }) {
  return <Button danger={danger} disabled={disabled} onClick={() => Modal.confirm({ title, okText: '确认', cancelText: '取消', okButtonProps: { danger }, onOk: async () => { try { await onConfirm() } catch (reason) { throw new Error(reportAdminError(reason, '操作失败')) } } })}>{label}</Button>
}
