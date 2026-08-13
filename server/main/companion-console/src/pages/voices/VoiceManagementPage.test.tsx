import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'

import { useAuthStore } from '../../auth/authStore'
import { VoiceManagementPage } from './VoiceManagementPage'

vi.mock('./TimbreManagementPage', () => ({ TimbreManagementPanel: () => <div>音色库面板</div> }))
vi.mock('./VoiceClonePage', () => ({ VoiceClonePanel: () => <div>音色克隆面板</div> }))

function LocationProbe() {
  const location = useLocation()
  return <output aria-label="当前地址">{location.pathname}{location.search}</output>
}

function session(superAdmin: 0 | 1) {
  useAuthStore.getState().setSessionForTest({
    token: 'token',
    user: { id: '1', username: superAdmin ? 'admin' : 'tester', superAdmin, status: 1 },
  })
}

function renderPage(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/voices" element={<><VoiceManagementPage /><LocationProbe /></>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('VoiceManagementPage', () => {
  beforeEach(() => session(0))

  it('normalizes an ordinary user away from an unauthorized tab', async () => {
    renderPage('/voices?tab=timbres&ttsModelId=TTS_EdgeTTS')

    expect(await screen.findByRole('tab', { name: '音色克隆' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('tab', { name: '音色库' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: '音色资源' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('当前地址')).toHaveTextContent('/voices?tab=clone&ttsModelId=TTS_EdgeTTS')
  })

  it('shows only the catalog and clone tabs to an administrator and normalizes resources', async () => {
    session(1)
    renderPage('/voices?tab=resources')

    expect(await screen.findByRole('tab', { name: '音色库' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: '音色克隆' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: '音色资源' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('当前地址')).toHaveTextContent('/voices?tab=timbres')
    expect(await screen.findByText('音色库面板')).toBeInTheDocument()
  })

  it('updates only the tab query when switching tabs', async () => {
    session(1)
    const user = userEvent.setup()
    renderPage('/voices?tab=timbres&ttsModelId=TTS_EdgeTTS')

    await user.click(await screen.findByRole('tab', { name: '音色克隆' }))

    expect(screen.getByLabelText('当前地址')).toHaveTextContent('/voices?tab=clone&ttsModelId=TTS_EdgeTTS')
    expect(await screen.findByText('音色克隆面板')).toBeInTheDocument()
  })

  it('reacts when permissions refresh while mounted', async () => {
    renderPage('/voices?tab=clone')
    expect(await screen.findByRole('tab', { name: '音色克隆' })).toBeInTheDocument()

    session(1)

    await waitFor(() => expect(screen.getByRole('tab', { name: '音色库' })).toBeInTheDocument())
    expect(screen.queryByRole('tab', { name: '音色资源' })).not.toBeInTheDocument()
  })
})
