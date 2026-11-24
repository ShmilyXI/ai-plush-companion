import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { roleLook, setCustomLook } from '../lib/roles'
import RoleEditor from './RoleEditor'

function stubRole(name: string) {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/roles/agent-a') return new Response(JSON.stringify({ data: {
      id: 'agent-a', name, userAddress: '', personality: '', systemPrompt: '', fromPreset: true, ttsModelId: null, ttsVoiceId: '',
    } }), { status: 200 })
    return new Response(JSON.stringify({ data: [] }), { status: 200 })
  }))
}

async function openEditor(name: string) {
  stubRole(name)
  render(<RoleEditor agentId="agent-a" agentName={name} onClose={() => {}} onSaved={() => {}} onDeleted={() => {}} />)
  const editor = await screen.findByRole('main', { name: '角色设置' })
  await within(editor).findByRole('textbox', { name: '名字' })
  return editor
}

describe('RoleEditor history', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('clears the conversation with this role only after an explicit confirmation', async () => {
    const calls: Array<{ url: string; method: string }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, method: init?.method || 'GET' })
      if (url === '/api/roles/agent-a') return new Response(JSON.stringify({ data: {
        id: 'agent-a', name: '紫萱', userAddress: '', personality: '', systemPrompt: '', fromPreset: true, ttsModelId: null, ttsVoiceId: '',
      } }), { status: 200 })
      return new Response(JSON.stringify({ data: [] }), { status: 200 })
    }))
    const cleared = vi.fn()
    render(<RoleEditor agentId="agent-a" agentName="紫萱" onClose={() => {}} onSaved={() => {}} onDeleted={() => {}} onHistoryCleared={cleared} />)
    const editor = await screen.findByRole('main', { name: '角色设置' })

    fireEvent.click(await within(editor).findByRole('button', { name: '清空和紫萱的对话记录' }))
    expect(within(editor).getByText(/清空后无法恢复/)).toBeVisible()
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false)

    fireEvent.click(within(editor).getByRole('button', { name: '确认清空' }))
    await waitFor(() => expect(cleared).toHaveBeenCalledTimes(1))
    expect(calls).toContainEqual({ url: '/api/history?agentId=agent-a', method: 'DELETE' })
    expect(within(editor).getByRole('status')).toHaveTextContent('和紫萱的对话记录已经清空')
  })
})

describe('RoleEditor figure', () => {
  afterEach(() => { vi.unstubAllGlobals(); setCustomLook('新朋友', null) })

  it('shows a preset role its own art, with no animal or colour to pick', async () => {
    const editor = await openEditor('年糕')
    expect(within(editor).queryByRole('radiogroup', { name: '动物' })).not.toBeInTheDocument()
    expect(within(editor).queryByRole('radiogroup', { name: '颜色' })).not.toBeInTheDocument()
    expect(within(editor).queryByText(/示例模型/)).not.toBeInTheDocument()
  })

  it('still lets a role started from blank pick an animal and a colour', async () => {
    const editor = await openEditor('新朋友')
    expect(within(editor).getByRole('radiogroup', { name: '动物' })).toBeInTheDocument()
    expect(within(editor).getByRole('radiogroup', { name: '颜色' })).toBeInTheDocument()
  })

  it('ignores an animal this device saved for a preset role earlier', () => {
    const own = roleLook('年糕')
    setCustomLook('年糕', { kind: 'rabbit', color: 'peach' })
    expect(roleLook('年糕')).toBe(own)
  })
})
