import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AccountPanel from './AccountPanel'

vi.mock('../lib/password', async (original) => ({
  ...await original<typeof import('../lib/password')>(),
  encryptPassword: vi.fn(async (password: string) => `enc:${password}`),
}))

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

describe('AccountPanel', () => {
  const handlers = {
    onClose: vi.fn(), onSignOut: vi.fn(), onAccountDeleted: vi.fn(),
  }

  function renderPanel() {
    return render(<AccountPanel roleCount={4} {...handlers} />)
  }

  /** 账号卡片读的资料和套餐。别的请求交给 other。 */
  function withProfile(other: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response> | Response) {
    return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/session') return json({ data: { phone: '13900000001', createDate: Date.now() - 5 * 86_400_000 } })
      if (String(input) === '/api/plan') return json({ data: { name: '内测版', maxRoles: 5 } })
      return other(input, init)
    })
  }

  beforeEach(() => {
    Object.values(handlers).forEach((handler) => handler.mockReset())
  })

  it('shows who is signed in, the plan with how many roles are used, and how long they have been together', async () => {
    vi.stubGlobal('fetch', withProfile(() => json({ data: {} })))
    renderPanel()

    expect(screen.getByRole('heading', { name: '个人中心' })).toBeVisible()
    expect(await screen.findByText('139****0001')).toBeVisible()
    expect(screen.getByText('内测版 · 角色 4/5 · 已陪伴 6 天')).toBeVisible()
    // 某个角色的东西不在个人中心。
    expect(screen.queryByText(/清空和/)).not.toBeInTheDocument()
  })

  it('sets a password with a code sent to the owner, then offers to change it', async () => {
    const fetchMock = withProfile((input) => String(input) === '/api/account/sms-code'
      ? json({ data: { sent: true } })
      : json({ data: { ok: true } }))
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: /设置密码/ }))
    expect(screen.getByRole('heading', { name: '设置密码' })).toBeVisible()
    expect(screen.getByText(/139\*\*\*\*0001/)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '给我的手机发验证码' }))

    fireEvent.change(await screen.findByRole('textbox', { name: '密码验证码' }), { target: { value: '123456' } })
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'weak' } })
    fireEvent.click(screen.getByRole('button', { name: '保存密码' }))
    expect(screen.getByRole('alert')).toHaveTextContent('密码需要8 到 32 位')

    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'NewPass123' } })
    fireEvent.click(screen.getByRole('button', { name: '保存密码' }))

    expect(await screen.findByRole('status')).toHaveTextContent('密码已设置')
    const saved = fetchMock.mock.calls.find(([input]) => String(input) === '/api/account/password')
    expect(JSON.parse(String((saved?.[1] as RequestInit).body))).toEqual({ code: '123456', password: 'enc:NewPass123' })
    expect(screen.getByRole('button', { name: /修改密码/ })).toBeVisible()
  })

  it('deletes the account only with a code sent to the owner and then hands back to sign-in', async () => {
    const fetchMock = withProfile((input) => String(input) === '/api/account/sms-code'
      ? json({ data: { sent: true } })
      : json({ data: { ok: true } }))
    const accountCalls = () => fetchMock.mock.calls.filter(([input]) => String(input).startsWith('/api/account/'))
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()

    fireEvent.click(screen.getByRole('button', { name: /注销账号/ }))
    expect(screen.getByText(/无法恢复/)).toBeVisible()
    expect(screen.queryByRole('button', { name: '确认注销账号' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '给我的手机发验证码' }))
    const code = await screen.findByRole('textbox', { name: '注销验证码' })
    fireEvent.click(screen.getByRole('button', { name: '确认注销账号' }))
    expect(screen.getByRole('alert')).toHaveTextContent('请输入短信里的验证码')
    expect(accountCalls()).toHaveLength(1)

    fireEvent.change(code, { target: { value: '654321' } })
    fireEvent.click(screen.getByRole('button', { name: '确认注销账号' }))

    await waitFor(() => expect(handlers.onAccountDeleted).toHaveBeenCalledTimes(1))
    expect(JSON.parse(String((accountCalls()[1][1] as RequestInit).body))).toEqual({ code: '654321' })
  })

  it('keeps the account when the server refuses the code', async () => {
    vi.stubGlobal('fetch', withProfile((input) => String(input) === '/api/account/sms-code'
      ? json({ data: { sent: true } })
      : json({ error: '手机验证码错误', code: 10075 }, 400)))
    renderPanel()

    fireEvent.click(screen.getByRole('button', { name: /注销账号/ }))
    fireEvent.click(screen.getByRole('button', { name: '给我的手机发验证码' }))
    fireEvent.change(await screen.findByRole('textbox', { name: '注销验证码' }), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: '确认注销账号' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('手机验证码错误')
    expect(handlers.onAccountDeleted).not.toHaveBeenCalled()
  })

  it('offers sign-out and the policies from the same place', () => {
    vi.stubGlobal('fetch', withProfile(() => json({ data: {} })))
    renderPanel()

    expect(screen.getByRole('link', { name: /隐私政策/ })).toHaveAttribute('href', '/privacy')
    expect(screen.getByRole('link', { name: /用户协议/ })).toHaveAttribute('href', '/terms')
    fireEvent.click(screen.getByRole('button', { name: '退出登录' }))
    expect(handlers.onSignOut).toHaveBeenCalledTimes(1)
  })

  it('switches between light, dark and following the system', () => {
    vi.stubGlobal('fetch', withProfile(() => json({ data: {} })))
    renderPanel()
    const pick = (name: string) => fireEvent.click(screen.getByRole('radio', { name }))

    expect(screen.getByRole('radio', { name: '跟随系统' })).toHaveAttribute('aria-checked', 'true')
    pick('深色')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(screen.getByRole('radio', { name: '深色' })).toHaveAttribute('aria-checked', 'true')
    pick('浅色')
    expect(document.documentElement.dataset.theme).toBe('light')
    // 跟随系统时不写这个属性，样式按系统的设置来。
    pick('跟随系统')
    expect(document.documentElement.dataset.theme).toBeUndefined()
  })
})
