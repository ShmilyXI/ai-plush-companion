import { Alert, Button, Checkbox, Divider, Form, Input, Modal, Select, Space, Tag, Typography } from 'antd'
import { useEffect, useState } from 'react'

import type { Capability, CapabilitySaveInput, McpToolSnapshot } from '../../api/capabilities'

interface McpFormValue {
  name: string
  description?: string
  transport: 'SSE' | 'STREAMABLE_HTTP'
  url: string
}

const health = {
  HEALTHY: { color: 'green', label: '连接正常' },
  UNHEALTHY: { color: 'red', label: '连接失败' },
  UNKNOWN: { color: 'default', label: '未检测' },
} as const

export function McpEditorModal({ open, capability, tools, secretStatus, saving, error, onCancel, onSave,
  onSaveSecret, onApprove }: {
  open: boolean
  capability: Capability | null
  tools: McpToolSnapshot[]
  secretStatus: Record<string, boolean>
  saving: boolean
  error: string
  onCancel: () => void
  onSave: (input: CapabilitySaveInput) => Promise<void> | void
  onSaveSecret: (name: string, value: string) => Promise<void> | void
  onApprove: (ids: string[]) => Promise<void> | void
}) {
  const [form] = Form.useForm<McpFormValue>()
  const [secretName, setSecretName] = useState('')
  const [secretValue, setSecretValue] = useState('')
  const [approvedIds, setApprovedIds] = useState<string[]>([])
  const isStdio = capability?.mcp?.transport === 'STDIO'

  useEffect(() => {
    if (!open) return
    const url = capability?.mcp?.connectionConfig.url
    form.setFieldsValue({
      name: capability?.name ?? '',
      description: capability?.description ?? '',
      transport: capability?.mcp?.transport === 'STREAMABLE_HTTP' ? 'STREAMABLE_HTTP' : 'SSE',
      url: typeof url === 'string' ? url : '',
    })
    setApprovedIds(tools.filter((tool) => tool.approved === 1 && tool.status === 'ACTIVE').map((tool) => tool.id))
    setSecretName('')
    setSecretValue('')
  }, [capability, form, open, tools])

  async function submit(values: McpFormValue) {
    await onSave({
      type: 'MCP_SERVER',
      name: values.name.trim(),
      description: values.description?.trim() || null,
      mcp: {
        transport: values.transport,
        connectionConfig: { url: values.url.trim(), headers: {} },
        secretRefs: capability?.mcp?.secretRefs ?? {},
        approvedCommandTemplate: capability?.mcp?.approvedCommandTemplate ?? null,
      },
    })
  }

  async function saveSecret() {
    const name = secretName.trim()
    if (!name || !secretValue) return
    await onSaveSecret(name, secretValue)
    setSecretValue('')
  }

  const state = health[capability?.mcp?.healthStatus ?? 'UNKNOWN']
  return <Modal width={820} title={capability ? '编辑 MCP 服务' : '新建 MCP 服务'} open={open} onCancel={onCancel}
    footer={<Space><Button onClick={onCancel} disabled={saving}>取消</Button><Button type="primary" loading={saving} disabled={isStdio} onClick={() => form.submit()}>保存 MCP</Button></Space>}
    closable={!saving} maskClosable={!saving} destroyOnHidden>
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
    {capability && <Space wrap style={{ marginBottom: 16 }}>
      <Typography.Text strong>连接状态</Typography.Text><Tag color={state.color}>{state.label}</Tag>
      {capability.mcp?.lastCheckedAt && <Typography.Text type="secondary">检测于 {new Date(capability.mcp.lastCheckedAt).toLocaleString()}</Typography.Text>}
    </Space>}
    {isStdio && <Alert type="warning" showIcon style={{ marginBottom: 16 }} message="stdio 连接只能通过已批准模板导入，控制台不提供任意命令编辑。" />}

    <Form form={form} layout="vertical" onFinish={submit} requiredMark="optional">
      <Space direction="vertical" style={{ width: '100%' }}>
        <Space align="start" wrap style={{ width: '100%' }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, whitespace: true, message: '请输入 MCP 名称' }]} style={{ minWidth: 300, flex: 1 }}><Input maxLength={128} disabled={isStdio} autoFocus /></Form.Item>
          <Form.Item name="transport" label="连接协议" rules={[{ required: true }]} style={{ width: 200 }}><Select disabled={isStdio} options={[
            { value: 'SSE', label: 'SSE' }, { value: 'STREAMABLE_HTTP', label: 'Streamable HTTP' },
          ]} /></Form.Item>
        </Space>
        <Form.Item name="description" label="用途说明"><Input.TextArea rows={2} maxLength={1000} disabled={isStdio} /></Form.Item>
        <Form.Item name="url" label="服务地址" rules={[{ required: true, type: 'url', message: '请输入有效的 HTTP 地址' }]}><Input placeholder="https://mcp.example/sse" disabled={isStdio} /></Form.Item>
      </Space>
    </Form>

    <Divider orientation="left" plain>密钥</Divider>
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space wrap>{Object.entries(secretStatus).map(([name, configured]) => <Tag color={configured ? 'green' : 'default'} key={name}>{name} {configured ? '已配置' : '未配置'}</Tag>)}</Space>
      {capability ? <Space align="end" wrap>
        <Form.Item label="密钥名称" style={{ marginBottom: 0 }}><Input aria-label="密钥名称" value={secretName} onChange={(event) => setSecretName(event.target.value)} placeholder="authorization" /></Form.Item>
        <Form.Item label="新密钥值" style={{ marginBottom: 0 }}><Input.Password aria-label="新密钥值" value={secretValue} onChange={(event) => setSecretValue(event.target.value)} autoComplete="new-password" /></Form.Item>
        <Button onClick={() => void saveSecret()} disabled={!secretName.trim() || !secretValue}>保存密钥</Button>
      </Space> : <Typography.Text type="secondary">先保存 MCP 服务，再配置密钥。密钥值不会回显。</Typography.Text>}
    </Space>

    {capability && <>
      <Divider orientation="left" plain>工具白名单</Divider>
      {tools.length ? <Space direction="vertical" style={{ width: '100%' }}>
        <Checkbox.Group value={approvedIds} onChange={(values) => setApprovedIds(values.map(String))}>
          <Space direction="vertical">{tools.map((tool) => <Checkbox key={tool.id} value={tool.id}
            disabled={tool.status === 'DRIFTED' || tool.status === 'MISSING'}>
            <Space><span>{tool.toolName}</span><Tag color={tool.status === 'ACTIVE' ? 'green' : tool.status === 'DISCOVERED' ? 'blue' : 'red'}>{tool.status}</Tag></Space>
          </Checkbox>)}</Space>
        </Checkbox.Group>
        <Button onClick={() => void onApprove(approvedIds)}>保存工具白名单</Button>
      </Space> : <Alert type="info" showIcon message="运行时连接成功后会同步工具列表。连接测试不会自动授权工具。" />}
    </>}
  </Modal>
}
