import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from '../api/http'
import { registerAccount } from '../auth/register'
import { RegisterPage } from './RegisterPage'

vi.mock('../auth/register', () => ({ registerAccount: vi.fn() }))

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname}{location.search}</div>
}

function renderRegister() {
  return render(
    <MemoryRouter initialEntries={['/register']}>
      <Routes>
        <Route path="/register" element={<><RegisterPage /><LocationProbe /></>} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

async function fillValidForm(username = 'new-user') {
  const user = userEvent.setup()
  await screen.findByAltText('验证码')
  await user.type(screen.getByPlaceholderText('用户名'), username)
  await user.type(screen.getByPlaceholderText('请输入密码'), 'ValidPass9!')
  await user.type(screen.getByPlaceholderText('再次输入密码'), 'ValidPass9!')
  await user.type(screen.getByPlaceholderText('五位验证码'), 'abcde')
  return user
}

describe('RegisterPage', () => {
  beforeEach(() => {
    vi.mocked(registerAccount).mockReset()
    vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => `blob:${(blob as Blob).size}`)
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
  })

  it('requires matching passwords before submitting', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: new Blob(['captcha']) })
    renderRegister()
    const user = userEvent.setup()
    await screen.findByAltText('验证码')
    await user.type(screen.getByPlaceholderText('用户名'), 'new-user')
    await user.type(screen.getByPlaceholderText('请输入密码'), 'ValidPass9!')
    await user.type(screen.getByPlaceholderText('再次输入密码'), 'Different9!')
    await user.type(screen.getByPlaceholderText('五位验证码'), 'abcde')
    await user.click(screen.getByRole('button', { name: '创建账号' }))

    expect(await screen.findByText('两次输入的密码不一致')).toBeVisible()
    expect(registerAccount).not.toHaveBeenCalled()
  })

  it('returns to login with the registered username', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: new Blob(['captcha']) })
    vi.mocked(registerAccount).mockResolvedValue()
    renderRegister()
    const user = await fillValidForm()

    await user.click(screen.getByRole('button', { name: '创建账号' }))

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/login?registered=new-user'))
  })

  it('keeps the backend error visible while refreshing captcha', async () => {
    vi.mocked(registerAccount).mockRejectedValue(new Error('账号已经注册'))
    const captchaRequest = vi.spyOn(http, 'get')
      .mockResolvedValueOnce({ data: new Blob(['first']) })
      .mockResolvedValueOnce({ data: new Blob(['second']) })
    renderRegister()
    const user = await fillValidForm()

    await user.click(screen.getByRole('button', { name: '创建账号' }))

    expect(await screen.findByText('账号已经注册')).toBeVisible()
    await waitFor(() => expect(captchaRequest).toHaveBeenCalledTimes(2))
    expect(screen.getByText('账号已经注册')).toBeVisible()
  })
})
