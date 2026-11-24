import { render, screen, within } from '@testing-library/react'
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
