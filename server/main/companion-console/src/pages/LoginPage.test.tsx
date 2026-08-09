import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from '../api/http'
import { useAuthStore } from '../auth/authStore'
import { getSafeRedirect } from '../auth/redirect'
import { LoginPage } from './LoginPage'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function LocationProbe() {
  return <div data-testid="location">{useLocation().pathname}</div>
}

function renderLogin(search = '') {
  return render(
    <MemoryRouter initialEntries={[`/login${search}`]}>
      <Routes>
        <Route path="*" element={<><LoginPage /><LocationProbe /></>} />
      </Routes>
    </MemoryRouter>,
  )
}

const realLogin = useAuthStore.getState().login

describe('LoginPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clearSession()
    useAuthStore.setState({ login: realLogin })
  })

  it('logs in without loading or rendering an image captcha', async () => {
    const user = userEvent.setup()
    const login = vi.fn().mockResolvedValue(undefined)
    useAuthStore.setState({ status: 'anonymous', login })
    const get = vi.spyOn(http, 'get')

    renderLogin()

    expect(screen.queryByLabelText('刷新验证码')).not.toBeInTheDocument()
    expect(screen.queryByAltText('验证码')).not.toBeInTheDocument()
    expect(screen.queryByPlaceholderText('五位验证码')).not.toBeInTheDocument()
    expect(get.mock.calls.some(([url]) => String(url).startsWith('/user/captcha'))).toBe(false)

    await user.type(screen.getByPlaceholderText('用户名或手机号'), 'admin')
    await user.type(screen.getByPlaceholderText('请输入密码'), 'secret')
    await user.click(screen.getByRole('button', { name: '进入管理台' }))

    expect(login).toHaveBeenCalledWith({ username: 'admin', password: 'secret' })
  })

  it('waits for the complete login promise before navigating to a safe redirect', async () => {
    const pendingLogin = deferred<void>()
    const login = vi.fn(() => pendingLogin.promise)
    useAuthStore.setState({ login })
    renderLogin('?redirect=%2Fprofiles')
    const user = userEvent.setup()
    await user.type(screen.getByPlaceholderText('用户名或手机号'), 'demo')
    await user.type(screen.getByPlaceholderText('请输入密码'), 'secret')
    await user.click(screen.getByRole('button', { name: '进入管理台' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/login')
    pendingLogin.resolve()
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/profiles'))
  })

  it('submits only once when the same form is submitted twice before login settles', async () => {
    const pendingLogin = deferred<void>()
    const login = vi.fn(() => pendingLogin.promise)
    useAuthStore.setState({ login })
    renderLogin()
    const user = userEvent.setup()
    await user.type(screen.getByPlaceholderText('用户名或手机号'), 'demo')
    await user.type(screen.getByPlaceholderText('请输入密码'), 'secret')
    const form = screen.getByRole('button', { name: '进入管理台' }).closest('form')
    if (!form) throw new Error('login form not found')

    fireEvent.submit(form)
    await waitFor(() => expect(login).toHaveBeenCalledTimes(1))
    fireEvent.submit(form)
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(login).toHaveBeenCalledTimes(1)
    pendingLogin.resolve()
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/dashboard'))
  })

  it('keeps the login page mounted until the real store finishes user info and applies redirect', async () => {
    const publicKey = '043bdbcae97b96aa266d34821ce120b150634f070f12ba55323ef6b0757673aaa6eb058476b4bcc36c60191382b0bd61083724055ed78f81b6af9ff1cd6925c006'
    vi.spyOn(http, 'get').mockImplementation((url) => {
      if (url === '/user/pub-config') return Promise.resolve({ data: { code: 0, data: { sm2PublicKey: publicKey } } }) as never
      return Promise.resolve({ data: { code: 0, data: { id: '7', username: 'demo', superAdmin: 0, status: 1 } } }) as never
    })
    vi.spyOn(http, 'post').mockResolvedValue({
      data: { code: 0, data: { token: 'session-token', expire: 43200, clientHash: 'browser' } },
    })
    renderLogin('?redirect=%2Fprofiles')
    const user = userEvent.setup()
    await user.type(screen.getByPlaceholderText('用户名或手机号'), 'demo')
    await user.type(screen.getByPlaceholderText('请输入密码'), 'secret')
    await user.click(screen.getByRole('button', { name: '进入管理台' }))

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/profiles'))
    expect(useAuthStore.getState().user?.id).toBe('7')
  })

  it('keeps the login error visible without refreshing captcha after a failed login', async () => {
    const get = vi.spyOn(http, 'get')
    const login = vi.fn().mockRejectedValue(new Error('账号或密码错误'))
    useAuthStore.setState({ login })
    renderLogin()
    const user = userEvent.setup()
    await user.type(screen.getByPlaceholderText('用户名或手机号'), 'demo')
    await user.type(screen.getByPlaceholderText('请输入密码'), 'wrong')
    await user.click(screen.getByRole('button', { name: '进入管理台' }))

    expect(await screen.findByText('账号或密码错误')).toBeVisible()
    expect(get.mock.calls.some(([url]) => String(url).startsWith('/user/captcha'))).toBe(false)
  })

  it('links anonymous users to registration', () => {
    renderLogin()

    expect(screen.getByRole('link', { name: '立即注册' })).toHaveAttribute('href', '/register')
  })

  it('shows registration success and prefills the account', () => {
    renderLogin('?registered=new-user')

    expect(screen.getByText('注册成功，请登录')).toBeVisible()
    expect(screen.getByPlaceholderText('用户名或手机号')).toHaveValue('new-user')
  })

  it.each(['//evil.test', '/\\evil.test', 'https://evil.test', 'javascript:alert(1)', '/%5cevil'])('rejects unsafe redirect %s', (redirect) => {
    expect(getSafeRedirect(`?redirect=${encodeURIComponent(redirect)}`)).toBe('/dashboard')
  })

})
