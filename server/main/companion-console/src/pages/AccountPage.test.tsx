import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as accountApi from '../api/account'
import { useAuthStore } from '../auth/authStore'
import { AccountPage } from './AccountPage'

vi.mock('../api/account', () => ({ changePassword: vi.fn() }))

const realLogout = useAuthStore.getState().logout

describe('AccountPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    useAuthStore.setState({ logout: realLogout })
    useAuthStore.getState().setSessionForTest({
      token: 'session-token',
      user: { id: '7', username: 'companion-demo', superAdmin: 0, status: 1 },
    })
  })

  it('shows account identity and changes the password after confirmation matches', async () => {
    vi.mocked(accountApi.changePassword).mockResolvedValue(undefined)
    const logout = vi.fn().mockResolvedValue(undefined)
    useAuthStore.setState({ logout })
    render(<MemoryRouter><AccountPage /></MemoryRouter>)

    expect(screen.getByRole('heading', { name: '账号资料', level: 1 })).toBeVisible()
    expect(screen.getByText('companion-demo')).toBeVisible()
    expect(screen.getByText('普通用户')).toBeVisible()
    await userEvent.type(screen.getByLabelText('当前密码'), 'old-secret')
    await userEvent.type(screen.getByLabelText('新密码'), 'new-secret')
    await userEvent.type(screen.getByLabelText('确认新密码'), 'new-secret')
    await userEvent.click(screen.getByRole('button', { name: '修改密码' }))

    expect(accountApi.changePassword).toHaveBeenCalledWith({ password: 'old-secret', newPassword: 'new-secret' })
    expect(logout).toHaveBeenCalled()
  })

  it('does not submit mismatched new passwords', async () => {
    render(<MemoryRouter><AccountPage /></MemoryRouter>)

    await userEvent.type(screen.getByLabelText('当前密码'), 'old-secret')
    await userEvent.type(screen.getByLabelText('新密码'), 'new-secret')
    await userEvent.type(screen.getByLabelText('确认新密码'), 'different')
    await userEvent.click(screen.getByRole('button', { name: '修改密码' }))

    expect(await screen.findByText('两次输入的新密码不一致')).toBeVisible()
    expect(accountApi.changePassword).not.toHaveBeenCalled()
  })
})
