import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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

  it('sends a code, starts the resend countdown and signs in', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => String(input) === '/api/auth/sms-code'
      ? json({ data: { sent: true } })
      : json({ data: { ok: true } }))
    vi.stubGlobal('fetch', fetchMock)
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '138 0013 8000' } })
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))

    expect(await screen.findByRole('status')).toHaveTextContent('验证码已发送')
    expect(screen.getByRole('button', { name: /秒后重发/ })).toBeDisabled()
    expect(bodyOf(fetchMock.mock.calls[0])).toEqual({ phone: '138 0013 8000' })

    fireEvent.change(screen.getByRole('textbox', { name: '验证码' }), { target: { value: '12a34 56' } })
    expect(screen.getByRole('textbox', { name: '验证码' })).toHaveValue('123456')
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    await waitFor(() => expect(onSignedIn).toHaveBeenCalledTimes(1))
    expect(bodyOf(fetchMock.mock.calls[1])).toEqual({ phone: '138 0013 8000', code: '123456', inviteCode: '' })
  })

  it('points a first-time phone at the invite code and lets it retry with the same sms code', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ error: '首次登录需要填写邀请码', code: 10210 }, 400))
      .mockResolvedValueOnce(json({ data: { ok: true } }))
    vi.stubGlobal('fetch', fetchMock)
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '13800138000' } })
    fireEvent.change(screen.getByRole('textbox', { name: '验证码' }), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('首次登录需要填写邀请码')
    const invite = screen.getByRole('textbox', { name: '邀请码' })
    expect(invite).toHaveAttribute('aria-invalid', 'true')
    expect(invite).toHaveFocus()
    expect(onSignedIn).not.toHaveBeenCalled()

    fireEvent.change(invite, { target: { value: ' abcd2345 ' } })
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    await waitFor(() => expect(onSignedIn).toHaveBeenCalledTimes(1))
    expect(bodyOf(fetchMock.mock.calls[1])).toEqual({ phone: '13800138000', code: '123456', inviteCode: 'abcd2345' })
  })

  it('shows the server message when the code is wrong', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: '手机验证码错误', code: 10075 }, 400)))
    render(<LoginPanel onSignedIn={onSignedIn} />)

    fireEvent.change(screen.getByRole('textbox', { name: '手机号' }), { target: { value: '13800138000' } })
    fireEvent.change(screen.getByRole('textbox', { name: '验证码' }), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('手机验证码错误')
    expect(screen.getByRole('textbox', { name: '邀请码' })).not.toHaveAttribute('aria-invalid', 'true')
    expect(onSignedIn).not.toHaveBeenCalled()
  })
})
