import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as profileApi from '../../api/profiles'
import { ProfileListPage } from './ProfileListPage'

describe('ProfileListPage', () => {
  beforeEach(() => {
    vi.spyOn(profileApi, 'listProfiles').mockResolvedValue([])
    vi.spyOn(profileApi, 'listTemplates').mockResolvedValue([
      { id: 'template-a', code: 'companion-a', name: '治愈伙伴', relationMode: 'friend' },
    ])
  })

  function renderPage() {
    function LocationPath() {
      return <output data-testid="location-path">{useLocation().pathname}</output>
    }
    return render(<MemoryRouter initialEntries={['/profiles']}><ProfileListPage /><LocationPath /></MemoryRouter>)
  }

  it('exposes the page title as the main heading', async () => {
    renderPage()

    const heading = await screen.findByRole('heading', { level: 1, name: '陪伴角色' })
    expect(heading.closest('.ant-pro-page-container')).not.toBeNull()
  })

  it('creates the first profile from the ordinary template catalog', async () => {
    const create = vi.spyOn(profileApi, 'createProfile').mockResolvedValue('profile-new')
    renderPage()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '创建陪伴角色' }))
    await user.click(screen.getByRole('combobox', { name: '模板来源' }))
    await user.click(await screen.findByText('治愈伙伴'))
    await user.type(screen.getByLabelText('新角色名称'), '小满')
    await user.click(screen.getByRole('button', { name: '创建角色' }))
    expect(create).toHaveBeenCalledWith('template-a', '小满', expect.anything())
  })

  it('opens a profile when the list row is activated', async () => {
    vi.mocked(profileApi.listProfiles).mockResolvedValue([{ ...profileApi.emptyProfile, id: 'profile-a', name: '小满' }])
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByText('小满'))

    expect(screen.getByTestId('location-path')).toHaveTextContent('/profiles/profile-a')
  })

  it('does not navigate when the delete action is activated', async () => {
    vi.mocked(profileApi.listProfiles).mockResolvedValue([{ ...profileApi.emptyProfile, id: 'profile-a', name: '小满' }])
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '删除小满' }))

    expect(await screen.findByText('删除陪伴角色')).toBeInTheDocument()
    expect(screen.getByTestId('location-path')).toHaveTextContent('/profiles')
  })

  it('keeps profile creation available when the template catalog is empty', async () => {
    vi.mocked(profileApi.listTemplates).mockResolvedValue([])
    renderPage()
    const user = userEvent.setup()

    const createButton = await screen.findByRole('button', { name: '创建陪伴角色' })
    expect(createButton).toBeEnabled()
    await user.click(createButton)

    expect(await screen.findByText('暂无可用角色模板')).toBeInTheDocument()
    expect(screen.getByTestId('location-path')).toHaveTextContent('/profiles')
  })
})
