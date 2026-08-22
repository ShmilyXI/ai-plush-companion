import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PlaygroundPage } from './PlaygroundPage'

vi.mock('../../api/profiles', () => ({ listProfiles: vi.fn(), listProfileModelOptions: vi.fn() }))
vi.mock('../../api/playground', () => ({ createPlaygroundSession: vi.fn(), sendPlaygroundInput: vi.fn(), streamPlaygroundEvents: vi.fn() }))

import { listProfileModelOptions, listProfiles } from '../../api/profiles'

describe('PlaygroundPage', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(listProfiles).mockResolvedValue([{ id: 'p1', name: '小夏', relationMode: 'friend', userAddress: '', personality: '', systemPrompt: '', companionCues: { laugh: false, sigh: false, hesitate: false, breathe: false }, screenExpressionEnabled: true, cameraPreferenceEnabled: false, templateId: null, llmModelId: null, llmModelName: null, ttsModelId: null, ttsModelName: null, ttsVoiceId: null, ttsVoiceName: null, ttsLanguage: null, createdAt: null, updatedAt: null, models: [], effectiveModels: [], skills: [] }])
    vi.mocked(listProfileModelOptions).mockResolvedValue([])
  })
  it('renders the fixed three-column controls', async () => {
    render(<PlaygroundPage />)
    expect(await screen.findByText('历史对话')).toBeVisible()
    expect(screen.getByText('运行配置')).toBeVisible()
    expect(screen.getByRole('button', { name: '创建虚拟会话' })).toBeVisible()
  })
})
