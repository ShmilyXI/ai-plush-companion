import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { Capability, McpToolSnapshot } from '../../api/capabilities'
import { McpEditorModal } from './McpEditorModal'

const capability: Capability = {
  id: 'mcp-search', type: 'MCP_SERVER', name: '搜索 MCP', description: '外部搜索工具', status: 'PUBLISHED',
  draftVersion: 1, publishedVersion: 1, executionPrompt: null, semanticThreshold: null, responseMode: null,
  timeoutMs: null, failureMessage: null, triggers: [], tools: [], plugin: null,
  mcp: {
    transport: 'SSE', connectionConfig: { url: 'https://mcp.example/sse', headers: { Authorization: 'must-not-render' } },
    secretRefs: { 'headers.Authorization': 'secret-auth' }, approvedCommandTemplate: null,
    healthStatus: 'HEALTHY', lastError: null, lastCheckedAt: '2026-08-16T01:00:00Z',
  }, createdAt: null, updatedAt: null,
}

const tools: McpToolSnapshot[] = [{
  id: 'tool-search', mcpServerId: 'server-search', toolName: 'mcp_search', inputSchemaJson: '{"type":"object"}',
  schemaSha256: 'a'.repeat(64), status: 'ACTIVE', approved: 1, syncedAt: null, createdAt: null, updatedAt: null,
}, {
  id: 'tool-new', mcpServerId: 'server-search', toolName: 'mcp_new', inputSchemaJson: '{"type":"object"}',
  schemaSha256: 'b'.repeat(64), status: 'DISCOVERED', approved: 0, syncedAt: null, createdAt: null, updatedAt: null,
}]

describe('McpEditorModal', () => {
  it('shows connection health and tool approval without displaying secret values', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined)
    render(<McpEditorModal open capability={capability} tools={tools} secretStatus={{ authorization: true }}
      saving={false} error="" onCancel={vi.fn()} onSave={vi.fn()} onSaveSecret={vi.fn()} onApprove={onApprove}
      onTest={vi.fn()} onSync={vi.fn()} />)

    expect(screen.getByText('连接正常')).toBeInTheDocument()
    expect(screen.getByText('authorization 已配置')).toBeInTheDocument()
    expect(screen.getByText('mcp_search')).toBeInTheDocument()
    expect(screen.getByText('mcp_new')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('must-not-render')).not.toBeInTheDocument()
    expect(screen.queryByText('must-not-render')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '保存工具白名单' }))
    await waitFor(() => expect(onApprove).toHaveBeenCalledWith(['tool-search']))
  })

  it('saves network connection settings and keeps secret values write-only', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    const onSaveSecret = vi.fn().mockResolvedValue(undefined)
    const view = render(<McpEditorModal open capability={null} tools={[]} secretStatus={{}}
      saving={false} error="" onCancel={vi.fn()} onSave={onSave} onSaveSecret={onSaveSecret} onApprove={vi.fn()}
      onTest={vi.fn()} onSync={vi.fn()} />)

    await userEvent.type(screen.getByLabelText('名称'), '知识库 MCP')
    await userEvent.type(screen.getByLabelText('服务地址'), 'https://mcp.example/http')
    await userEvent.click(screen.getByRole('button', { name: '保存 MCP' }))
    await waitFor(() => expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      type: 'MCP_SERVER', name: '知识库 MCP', mcp: expect.objectContaining({
        transport: 'SSE', connectionConfig: { url: 'https://mcp.example/http', headers: {} },
      }),
    })))

    view.rerender(<McpEditorModal open capability={capability} tools={[]} secretStatus={{}}
      saving={false} error="" onCancel={vi.fn()} onSave={onSave} onSaveSecret={onSaveSecret} onApprove={vi.fn()}
      onTest={vi.fn()} onSync={vi.fn()} />)
    await userEvent.type(screen.getByLabelText('密钥路径'), 'headers.Authorization')
    await userEvent.type(screen.getByLabelText('新密钥值'), 'Bearer write-only')
    await userEvent.click(screen.getByRole('button', { name: '保存密钥' }))
    await waitFor(() => expect(onSaveSecret).toHaveBeenCalledWith('headers.Authorization', 'Bearer write-only'))
    expect(screen.getByLabelText('新密钥值')).toHaveValue('')
  })

  it('keeps connection testing separate from tool synchronization', async () => {
    const onTest = vi.fn().mockResolvedValue(undefined)
    const onSync = vi.fn().mockResolvedValue(undefined)
    render(<McpEditorModal open capability={capability} tools={tools} secretStatus={{}}
      saving={false} error="" onCancel={vi.fn()} onSave={vi.fn()} onSaveSecret={vi.fn()} onApprove={vi.fn()}
      onTest={onTest} onSync={onSync} />)

    await userEvent.click(screen.getByRole('button', { name: '测试连接' }))
    await userEvent.click(screen.getByRole('button', { name: '同步工具' }))

    await waitFor(() => expect(onTest).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(onSync).toHaveBeenCalledTimes(1))
  })
})
