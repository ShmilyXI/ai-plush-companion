import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as profileApi from '../../api/profiles'
import { ProfileListPage } from './ProfileListPage'

describe('ProfileListPage', () => {
  beforeEach(() => {
    vi.spyOn(profileApi, 'listProfiles').mockResolvedValue([])
    vi.spyOn(profileApi, 'listTemplates').mockResolvedValue([
      { id: 'template-a', code: 'companion-a', name: '治愈伙伴', relationMode: 'friend', cues: ['laugh'] },
    ])
  })

  it('creates the first profile from the ordinary template catalog', async () => {
    const create = vi.spyOn(profileApi, 'createProfile').mockResolvedValue('profile-new')
    render(<MemoryRouter><ProfileListPage /></MemoryRouter>)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '创建陪伴角色' }))
    await user.click(screen.getByRole('combobox', { name: '模板来源' }))
    await user.click(await screen.findByText('治愈伙伴'))
    await user.type(screen.getByLabelText('新角色名称'), '小满')
    await user.click(screen.getByRole('button', { name: '创建角色' }))
    expect(create).toHaveBeenCalledWith('template-a', '小满', expect.anything())
  })
})
