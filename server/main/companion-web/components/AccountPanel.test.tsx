import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AccountPanel from './AccountPanel'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

describe('AccountPanel', () => {
  const handlers = {
    onClose: vi.fn(), onHistoryCleared: vi.fn(), onSignOut: vi.fn(), onAccountDeleted: vi.fn(),
  }

  function renderPanel() {
    return render(<AccountPanel roleName="紫萱" agentId="agent-a" {...handlers} />)
  }

  beforeEach(() => {
    Object.values(handlers).forEach((handler) => handler.mockReset())
  })

  it('clears the conversation with one role only after an explicit confirmation', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json({ data: { deleted: 12 } }))
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()

    fireEvent.click(screen.getByRole('button', { name: /清空和紫萱的对话记录/ }))
    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByText(/清空后无法恢复/)).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '确认清空' }))

    await waitFor(() => expect(handlers.onHistoryCleared).toHaveBeenCalledTimes(1))
    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/history?agentId=agent-a')
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe('DELETE')
    expect(await screen.findByRole('status')).toHaveTextContent('已经清空')
  })

  it('deletes the account only with a code sent to the owner and then hands back to sign-in', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => String(input) === '/api/account/sms-code'
      ? json({ data: { sent: true } })
      : json({ data: { ok: true } }))
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()

    fireEvent.click(screen.getByRole('button', { name: /注销账号/ }))
    expect(screen.getByText(/无法恢复/)).toBeVisible()
    expect(screen.queryByRole('button', { name: '确认注销账号' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '给我的手机发验证码' }))
    const code = await screen.findByRole('textbox', { name: '注销验证码' })
    fireEvent.click(screen.getByRole('button', { name: '确认注销账号' }))
    expect(screen.getByRole('alert')).toHaveTextContent('请输入短信里的验证码')
    expect(fetchMock).toHaveBeenCalledTimes(1)

    fireEvent.change(code, { target: { value: '654321' } })
    fireEvent.click(screen.getByRole('button', { name: '确认注销账号' }))

    await waitFor(() => expect(handlers.onAccountDeleted).toHaveBeenCalledTimes(1))
    expect(JSON.parse(String((fetchMock.mock.calls[1][1] as RequestInit).body))).toEqual({ code: '654321' })
  })

  it('keeps the account when the server refuses the code', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => String(input) === '/api/account/sms-code'
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
    vi.stubGlobal('fetch', vi.fn())
    renderPanel()

    expect(screen.getByRole('link', { name: /隐私政策/ })).toHaveAttribute('href', '/privacy')
    expect(screen.getByRole('link', { name: /用户协议/ })).toHaveAttribute('href', '/terms')
    fireEvent.click(screen.getByRole('button', { name: '退出登录' }))
    expect(handlers.onSignOut).toHaveBeenCalledTimes(1)
  })
})
