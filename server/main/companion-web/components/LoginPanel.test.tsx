import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoginPanel from './LoginPanel'

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
    expect(bodyOf(fetchMock.mock.calls[1])).toEqual({
      phone: '138 0013 8000', code: '123456', inviteCode: '', consentVersion: '2026-11-20.2',
    })
  })

  it('points a first-time phone at the invite code and lets it retry with the same sms code', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ error: '首次登录需要填写邀请码', code: 10210 }, 400))
      .mockResolvedValueOnce(json({ data: { ok: true } }))
    vi.stubGlobal('fetch', fetchMock)
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '13800138000' } })
    fireEvent.change(screen.getByRole('textbox', { name: '验证码' }), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('首次登录需要填写邀请码')
    const invite = screen.getByRole('textbox', { name: '邀请码' })
    expect(invite).toHaveAttribute('aria-invalid', 'true')
    expect(invite).toHaveFocus()
    expect(onSignedIn).not.toHaveBeenCalled()

    fireEvent.change(invite, { target: { value: ' abcd2345 ' } })
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    await waitFor(() => expect(onSignedIn).toHaveBeenCalledTimes(1))
    expect(bodyOf(fetchMock.mock.calls[1])).toEqual({
      phone: '13800138000', code: '123456', inviteCode: 'abcd2345', consentVersion: '2026-11-20.2',
    })
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
    expect(screen.getByRole('textbox', { name: '邀请码' })).not.toHaveAttribute('aria-invalid', 'true')
    expect(onSignedIn).not.toHaveBeenCalled()
  })
})
