import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoginPanel from './LoginPanel'

// 密码加密要先取服务端公钥，这里换成一眼能看出来的假密文。
vi.mock('../lib/password', async (original) => ({
  ...await original<typeof import('../lib/password')>(),
  encryptPassword: vi.fn(async (password: string) => `enc:${password}`),
}))

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

function bodyOf(call: unknown[]) {
  return JSON.parse(String((call[1] as RequestInit).body)) as Record<string, unknown>
}

describe('LoginPanel', () => {
  const onSignedIn = vi.fn()

  beforeEach(() => {
    onSignedIn.mockReset()
  })

  it('rejects a malformed phone number without calling the server', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '12345' } })
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))

    expect(screen.getByRole('alert')).toHaveTextContent('请输入正确的手机号')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('opens the image captcha in a popup, and sends nothing until it is filled in', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    render(<LoginPanel onSignedIn={onSignedIn} />)

    // 页面上平时没有图形验证码，点了“获取验证码”才弹出来。
    expect(screen.queryByRole('dialog', { name: '图形验证码' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '13800138000' } })
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
    const dialog = screen.getByRole('dialog', { name: '图形验证码' })

    fireEvent.click(within(dialog).getByRole('button', { name: '发送验证码' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('请输入图片里的字符')
    expect(fetchMock).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('keeps the popup open with a new picture after a wrong captcha, and tapping the picture changes it too', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: '图形验证码错误', code: 10067 }, 400)))
    render(<LoginPanel onSignedIn={onSignedIn} />)
    const captchaImage = () => document.querySelector('.captcha-image img')?.getAttribute('src')

    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '13800138000' } })
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
    const dialog = screen.getByRole('dialog', { name: '图形验证码' })
    const first = captchaImage()
    fireEvent.change(within(dialog).getByRole('textbox', { name: '图片里的字符' }), { target: { value: 'wrong' } })
    fireEvent.click(within(dialog).getByRole('button', { name: '发送验证码' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('图形验证码错误')
    const second = captchaImage()
    expect(second).not.toBe(first)
    expect(within(dialog).getByRole('textbox', { name: '图片里的字符' })).toHaveValue('')
    fireEvent.click(within(dialog).getByRole('button', { name: '换一张图形验证码' }))
    expect(captchaImage()).not.toBe(second)
  })

  it('closes the popup and explains in the form when sending fails for another reason', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: '验证码发送太频繁，请稍后再试', code: 500 }, 400)))
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '13800138000' } })
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
    fireEvent.change(screen.getByRole('textbox', { name: '图片里的字符' }), { target: { value: 'aB3dE' } })
    fireEvent.click(screen.getByRole('button', { name: '发送验证码' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByRole('alert')).toHaveTextContent('验证码发送太频繁')
  })

  it('sends a code, starts the resend countdown and signs in', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => String(input) === '/api/auth/sms-code'
      ? json({ data: { sent: true } })
      : json({ data: { ok: true } }))
    vi.stubGlobal('fetch', fetchMock)
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '138 0013 8000' } })
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
    const image = document.querySelector('.captcha-image img')?.getAttribute('src')
    fireEvent.change(screen.getByRole('textbox', { name: '图片里的字符' }), { target: { value: ' aB3dE ' } })
    fireEvent.click(screen.getByRole('button', { name: '发送验证码' }))

    expect(await screen.findByRole('status')).toHaveTextContent('验证码已发送')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /秒后重发/ })).toBeDisabled()
    const sent = bodyOf(fetchMock.mock.calls[0])
    expect(sent).toMatchObject({ phone: '138 0013 8000', captcha: 'aB3dE' })
    // 图片地址里的编号和交回去核对的是同一个。
    expect(image).toBe(`/api/auth/captcha?id=${String(sent.captchaId)}`)

    fireEvent.change(screen.getByRole('textbox', { name: '验证码' }), { target: { value: '12a34 56' } })
    expect(screen.getByRole('textbox', { name: '验证码' })).toHaveValue('123456')
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    await waitFor(() => expect(onSignedIn).toHaveBeenCalledTimes(1))
    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/login')
    expect(bodyOf(fetchMock.mock.calls[1])).toEqual({ phone: '138 0013 8000', code: '123456', consentVersion: '2026-11-20.2' })
  })

  it('takes an unregistered phone to sign-up with its number and code, and registers it with a password and invite', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ error: '手机号码未注册', code: 10071 }, 400))
      .mockResolvedValueOnce(json({ error: '新手机号注册需要填写邀请码', code: 10210 }, 400))
      .mockResolvedValueOnce(json({ data: { ok: true } }))
    vi.stubGlobal('fetch', fetchMock)
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '13800138000' } })
    fireEvent.change(screen.getByRole('textbox', { name: '验证码' }), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    // 转到注册，手机号和验证码都还在，只差密码。
    expect(await screen.findByRole('status')).toHaveTextContent('这个手机号还没有注册')
    expect(screen.getByRole('tab', { name: '注册' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('textbox', { name: '手机号' })).toHaveValue('13800138000')
    expect(screen.getByRole('textbox', { name: '验证码' })).toHaveValue('123456')
    await waitFor(() => expect(screen.getByLabelText('设置密码')).toHaveFocus())

    // 密码太简单，不提交。
    fireEvent.change(screen.getByLabelText('设置密码'), { target: { value: 'abc12345' } })
    fireEvent.click(screen.getByRole('button', { name: '注册' }))
    expect(screen.getByRole('alert')).toHaveTextContent('密码需要8 到 32 位')
    expect(fetchMock).toHaveBeenCalledTimes(1)

    // 没填邀请码：服务端要邀请码，验证码不作废，补上就能再注册。
    fireEvent.change(screen.getByLabelText('设置密码'), { target: { value: 'Abc12345' } })
    fireEvent.click(screen.getByRole('button', { name: '注册' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('新手机号注册需要填写邀请码')
    const invite = screen.getByRole('textbox', { name: '邀请码' })
    expect(invite).toHaveAttribute('aria-invalid', 'true')

    fireEvent.change(invite, { target: { value: ' abcd2345 ' } })
    fireEvent.click(screen.getByRole('button', { name: '注册' }))
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledTimes(1))
    expect(fetchMock.mock.calls[2][0]).toBe('/api/auth/register')
    expect(bodyOf(fetchMock.mock.calls[2])).toEqual({
      phone: '13800138000', code: '123456', password: 'enc:Abc12345', inviteCode: 'abcd2345', consentVersion: '2026-11-20.2',
    })
  })

  it('signs in with a password, and offers the code way back when the password is forgotten', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json({ data: { ok: true } }))
    vi.stubGlobal('fetch', fetchMock)
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.click(screen.getByRole('button', { name: '用密码登录' }))
    expect(screen.queryByRole('textbox', { name: '验证码' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '13800138000' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'Abc12345' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    await waitFor(() => expect(onSignedIn).toHaveBeenCalledTimes(1))
    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/login-password')
    expect(bodyOf(fetchMock.mock.calls[0])).toEqual({ phone: '13800138000', password: 'enc:Abc12345' })

    fireEvent.click(screen.getByRole('button', { name: '忘记密码？用验证码登录' }))
    expect(screen.getByRole('textbox', { name: '验证码' })).toBeInTheDocument()
  })

  it('sends a phone that is already registered back to sign-in', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: '手机号码已注册', code: 10070 }, 400)))
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.click(screen.getByRole('tab', { name: '注册' }))
    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '13800138000' } })
    fireEvent.change(screen.getByRole('textbox', { name: '验证码' }), { target: { value: '123456' } })
    fireEvent.change(screen.getByLabelText('设置密码'), { target: { value: 'Abc12345' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '注册' }))

    expect(await screen.findByRole('status')).toHaveTextContent('已经注册过了')
    expect(screen.getByRole('tab', { name: '登录' })).toHaveAttribute('aria-selected', 'true')
  })

  it('does not sign in until the age and terms box is ticked', async () => {
    const fetchMock = vi.fn(async () => json({ data: { ok: true } }))
    vi.stubGlobal('fetch', fetchMock)
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '13800138000' } })
    fireEvent.change(screen.getByRole('textbox', { name: '验证码' }), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    expect(screen.getByRole('alert')).toHaveTextContent('请先确认已满 18 周岁')
    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByRole('link', { name: '《用户协议》' })).toHaveAttribute('href', '/terms')
    expect(screen.getByRole('link', { name: '《隐私政策》' })).toHaveAttribute('href', '/privacy')

    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '登录' }))
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledTimes(1))
  })

  it('shows the server message when the code is wrong', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: '手机验证码错误', code: 10075 }, 400)))
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '13800138000' } })
    fireEvent.change(screen.getByRole('textbox', { name: '验证码' }), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('手机验证码错误')
    expect(onSignedIn).not.toHaveBeenCalled()
  })
})
