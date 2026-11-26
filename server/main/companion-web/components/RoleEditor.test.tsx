import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { roleLook, setCustomLook, setOutfit } from '../lib/roles'
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
    expect(roleLook('年糕')).toEqual(own)
  })

  it('lets 年糕 switch outfits, previews the choice and remembers it on this device after saving', async () => {
    const saved = vi.fn()
    // 测试环境没有本地存储，用一个只在内存里的代替。
    const stored = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => stored.get(key) ?? null,
      setItem: (key: string, value: string) => { stored.set(key, String(value)) },
      removeItem: (key: string) => { stored.delete(key) },
    })
    stubRole('年糕')
    render(<RoleEditor agentId="agent-a" agentName="年糕" onClose={() => {}} onSaved={saved} onDeleted={() => {}} />)
    const editor = await screen.findByRole('main', { name: '角色设置' })
    await within(editor).findByRole('textbox', { name: '名字' })
    const outfits = within(editor).getByRole('radiogroup', { name: '装扮' })
    expect(within(outfits).getAllByRole('radio').map((item) => item.textContent)).toEqual(['主播装', '大小姐', '银发卫衣', 'Q 版', '橘猫卫衣'])
    expect(within(outfits).getByRole('radio', { name: '主播装' })).toHaveAttribute('aria-checked', 'true')

    fireEvent.click(within(outfits).getByRole('radio', { name: '大小姐' }))
    expect(within(outfits).getByRole('radio', { name: '大小姐' })).toHaveAttribute('aria-checked', 'true')
    expect(editor.querySelector('.look-preview img[src="/roles/niangao-lady.webp"]')).not.toBeNull()
    // 只是预览：没保存之前，页面上用的还是原来那套。
    expect(roleLook('年糕').body).toBe('/roles/niangao-host.webp')

    fireEvent.click(within(editor).getByRole('button', { name: '保存' }))
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1))
    expect(window.localStorage.getItem('companion.outfit.agent-a')).toBe('lady')
    expect(roleLook('年糕').body).toBe('/roles/niangao-lady.webp')
    setOutfit('年糕', null)
  })

  it('offers no outfits to a preset role that has only one look', async () => {
    const editor = await openEditor('苏念')
    expect(within(editor).queryByRole('radiogroup', { name: '装扮' })).not.toBeInTheDocument()
  })
})

describe('RoleEditor voice', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('groups voices by model and switches the model when a voice from another model is chosen', async () => {
    const puts: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/roles/agent-a' && init?.method === 'PUT') {
        puts.push(JSON.parse(String(init.body)))
        return new Response(JSON.stringify({ data: { id: 'agent-a' } }), { status: 200 })
      }
      if (url === '/api/roles/agent-a') return new Response(JSON.stringify({ data: {
        id: 'agent-a', name: '紫萱', userAddress: '', personality: '', systemPrompt: '', fromPreset: true,
        ttsModelId: 'TTS_EdgeTTS', ttsVoiceId: 'edge-1',
      } }), { status: 200 })
      if (url.startsWith('/api/voices')) return new Response(JSON.stringify({ data: [
        { id: 'edge-1', name: 'Edge 晓晓', ttsModelId: 'TTS_EdgeTTS', ttsModelName: 'Edge语音' },
        { id: 'doubao-1', name: '豆包女声-Vivi', ttsModelId: 'TTS_DoubaoSeedTTS2', ttsModelName: '豆包语音 SeedTTS 2.0' },
      ] }), { status: 200 })
      return new Response(JSON.stringify({ data: [] }), { status: 200 })
    }))
    render(<RoleEditor agentId="agent-a" agentName="紫萱" onClose={() => {}} onSaved={() => {}} onDeleted={() => {}} />)
    const editor = await screen.findByRole('main', { name: '角色设置' })
    fireEvent.click(await within(editor).findByRole('button', { name: '声音：Edge 晓晓，点一下换声音' }))
    const picker = within(editor).getByRole('dialog', { name: '选择声音' })
    // 来自两个模型时可以按来源筛。
    expect(within(within(picker).getByRole('radiogroup', { name: '来源' })).getByRole('radio', { name: '豆包语音 SeedTTS 2.0' })).toBeInTheDocument()

    fireEvent.click(within(picker).getByRole('radio', { name: '豆包女声-Vivi' }))
    expect(within(editor).queryByRole('dialog', { name: '选择声音' })).not.toBeInTheDocument()
    expect(within(editor).getByRole('button', { name: '声音：豆包女声-Vivi，点一下换声音' })).toBeInTheDocument()
    fireEvent.click(within(editor).getByRole('button', { name: '保存' }))
    await waitFor(() => expect(puts).toHaveLength(1))
    expect(puts[0]).toMatchObject({ ttsVoiceId: 'doubao-1', ttsModelId: 'TTS_DoubaoSeedTTS2' })
  })

  it('does not send a model when the voice stays on the same model', async () => {
    const puts: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/roles/agent-a' && init?.method === 'PUT') {
        puts.push(JSON.parse(String(init.body)))
        return new Response(JSON.stringify({ data: { id: 'agent-a' } }), { status: 200 })
      }
      if (url === '/api/roles/agent-a') return new Response(JSON.stringify({ data: {
        id: 'agent-a', name: '紫萱', userAddress: '', personality: '', systemPrompt: '', fromPreset: true,
        ttsModelId: 'TTS_EdgeTTS', ttsVoiceId: 'edge-1',
      } }), { status: 200 })
      if (url.startsWith('/api/voices')) return new Response(JSON.stringify({ data: [
        { id: 'edge-1', name: 'Edge 晓晓', ttsModelId: 'TTS_EdgeTTS' },
        { id: 'edge-2', name: 'Edge 云希', ttsModelId: 'TTS_EdgeTTS' },
      ] }), { status: 200 })
      return new Response(JSON.stringify({ data: [] }), { status: 200 })
    }))
    render(<RoleEditor agentId="agent-a" agentName="紫萱" onClose={() => {}} onSaved={() => {}} onDeleted={() => {}} />)
    const editor = await screen.findByRole('main', { name: '角色设置' })
    fireEvent.click(await within(editor).findByRole('button', { name: '声音：Edge 晓晓，点一下换声音' }))
    fireEvent.click(within(within(editor).getByRole('dialog', { name: '选择声音' })).getByRole('radio', { name: 'Edge 云希' }))
    fireEvent.click(within(editor).getByRole('button', { name: '保存' }))
    await waitFor(() => expect(puts).toHaveLength(1))
    expect(puts[0].ttsVoiceId).toBe('edge-2')
    expect(puts[0]).not.toHaveProperty('ttsModelId')
  })
})

describe('RoleEditor voice preview', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

  function stubPreview(preview: () => Response) {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      calls.push(url)
      if (url === '/api/roles/agent-a') return new Response(JSON.stringify({ data: {
        id: 'agent-a', name: '紫萱', userAddress: '', personality: '', systemPrompt: '', fromPreset: true,
        ttsModelId: 'TTS_EdgeTTS', ttsVoiceId: 'edge-1',
      } }), { status: 200 })
      if (url.startsWith('/api/voices/')) return preview()
      if (url.startsWith('/api/voices')) return new Response(JSON.stringify({ data: [
        { id: 'edge-1', name: 'Edge 晓晓', ttsModelId: 'TTS_EdgeTTS' },
      ] }), { status: 200 })
      return new Response(JSON.stringify({ data: [] }), { status: 200 })
    }))
    return calls
  }

  it('plays the selected voice and stops on a second click', async () => {
    const calls = stubPreview(() => new Response(new Blob(['ID3']), { status: 200, headers: { 'Content-Type': 'audio/mpeg' } }))
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:voice'), revokeObjectURL: vi.fn() }))
    const play = vi.spyOn(window.HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
    const pause = vi.spyOn(window.HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
    render(<RoleEditor agentId="agent-a" agentName="紫萱" onClose={() => {}} onSaved={() => {}} onDeleted={() => {}} />)
    const editor = await screen.findByRole('main', { name: '角色设置' })
    await within(editor).findByRole('button', { name: '声音：Edge 晓晓，点一下换声音' })

    fireEvent.click(within(editor).getByRole('button', { name: '试听这个声音' }))
    await waitFor(() => expect(play).toHaveBeenCalledTimes(1))
    expect(calls).toContain('/api/voices/edge-1/preview')
    const stop = await within(editor).findByRole('button', { name: '停止试听' })

    fireEvent.click(stop)
    expect(pause).toHaveBeenCalled()
    expect(within(editor).getByRole('button', { name: '试听这个声音' })).toBeInTheDocument()
  })

  it('shows why a preview failed', async () => {
    stubPreview(() => new Response(JSON.stringify({ error: '这个声音暂时不能试听' }), { status: 400 }))
    render(<RoleEditor agentId="agent-a" agentName="紫萱" onClose={() => {}} onSaved={() => {}} onDeleted={() => {}} />)
    const editor = await screen.findByRole('main', { name: '角色设置' })
    await within(editor).findByRole('button', { name: '声音：Edge 晓晓，点一下换声音' })

    fireEvent.click(within(editor).getByRole('button', { name: '试听这个声音' }))
    expect(await within(editor).findByText('这个声音暂时不能试听')).toBeInTheDocument()
    expect(within(editor).getByRole('button', { name: '试听这个声音' })).toBeEnabled()
  })
})

describe('RoleEditor voice scenes and official demos', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

  it('groups Doubao voices by scene and plays the official demo without asking the server to synthesize', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      calls.push(url)
      if (url === '/api/roles/agent-a') return new Response(JSON.stringify({ data: {
        id: 'agent-a', name: '紫萱', userAddress: '', personality: '', systemPrompt: '', fromPreset: true,
        ttsModelId: 'TTS_EdgeTTS', ttsVoiceId: 'edge-1',
      } }), { status: 200 })
      if (url.startsWith('/api/voices')) return new Response(JSON.stringify({ data: [
        { id: 'db-1', name: 'Vivi 2.0', ttsModelId: 'TTS_DoubaoSeedTTS2', ttsModelName: '豆包语音', scene: '通用场景', voiceDemo: 'https://cdn.example/vivi.mp3' },
        { id: 'db-2', name: '魅力女友 2.0', ttsModelId: 'TTS_DoubaoSeedTTS2', ttsModelName: '豆包语音', scene: '角色扮演', voiceDemo: 'https://cdn.example/meili.mp3' },
        { id: 'edge-1', name: 'Edge 晓晓', ttsModelId: 'TTS_EdgeTTS', ttsModelName: 'Edge语音' },
      ] }), { status: 200 })
      return new Response(JSON.stringify({ data: [] }), { status: 200 })
    }))
    const sources: string[] = []
    vi.spyOn(window.HTMLMediaElement.prototype, 'play').mockImplementation(function (this: HTMLMediaElement) {
      sources.push(this.src)
      return Promise.resolve()
    })
    render(<RoleEditor agentId="agent-a" agentName="紫萱" onClose={() => {}} onSaved={() => {}} onDeleted={() => {}} />)
    const editor = await screen.findByRole('main', { name: '角色设置' })
    fireEvent.click(await within(editor).findByRole('button', { name: '声音：Edge 晓晓，点一下换声音' }))
    const picker = within(editor).getByRole('dialog', { name: '选择声音' })
    const scenes = within(within(picker).getByRole('radiogroup', { name: '场景' })).getAllByRole('radio').map((item) => item.getAttribute('aria-label'))
    expect(scenes).toEqual(['全部，3 个', '通用，2 个', '角色扮演，1 个'])

    fireEvent.click(within(picker).getByRole('radio', { name: '角色扮演，1 个' }))
    const cards = within(within(picker).getByRole('radiogroup', { name: '声音' })).getAllByRole('radio')
    expect(cards.map((item) => item.getAttribute('aria-label'))).toEqual(['魅力女友'])

    // 选之前先在选择页里试听，用官方的试听音频。
    fireEvent.click(within(picker).getByRole('button', { name: '试听魅力女友' }))
    await waitFor(() => expect(sources).toEqual(['https://cdn.example/meili.mp3']))
    fireEvent.click(cards[0])
    expect(within(editor).getByRole('button', { name: '声音：魅力女友，点一下换声音' })).toBeInTheDocument()
    expect(calls.some((url) => url.includes('/preview'))).toBe(false)
  })
})
