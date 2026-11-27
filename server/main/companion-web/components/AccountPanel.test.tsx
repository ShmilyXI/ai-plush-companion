import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AccountPanel from './AccountPanel'

// jsdom 里没有真正的图片解码和 canvas，裁图换成直接给一张固定的头像。
vi.mock('../lib/avatarImage', async (original) => ({
  ...await original<typeof import('../lib/avatarImage')>(),
  avatarFromFile: vi.fn(async (file: File) => {
    if (!file.type.startsWith('image/')) throw new Error('请选择一张图片')
    return 'data:image/jpeg;base64,/9j/NEW'
  }),
}))

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

  it('changes the nickname and avatar from the profile card and shows them right away', async () => {
    const fetchMock = withProfile((input, init) => String(input) === '/api/account/profile'
      ? json({ data: { nickname: '小林', avatar: 'data:image/jpeg;base64,/9j/NEW' } })
      : json({ data: {} }))
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()

    // 卡片先出来，资料是后读到的：等卡片上有了手机号再点，输入框才有对的提示。
    const profileCard = await screen.findByRole('button', { name: '编辑资料' })
    await waitFor(() => expect(profileCard).toHaveTextContent('139****0001'))
    fireEvent.click(profileCard)
    expect(screen.getByRole('heading', { name: '编辑资料' })).toBeVisible()
    // 没设昵称时输入框里提示的是手机号，不填就显示它。
    const nickname = screen.getByRole('textbox', { name: '昵称' })
    expect(nickname).toHaveAttribute('placeholder', '139****0001')

    // 不是图片的文件不收。
    const picker = screen.getByLabelText('选择头像图片')
    fireEvent.change(picker, { target: { files: [new File(['x'], 'notes.txt', { type: 'text/plain' })] } })
    expect(await screen.findByRole('alert')).toHaveTextContent('请选择一张图片')

    fireEvent.change(picker, { target: { files: [new File(['x'], 'me.png', { type: 'image/png' })] } })
    expect(await screen.findByRole('img', { name: '头像预览' })).toHaveAttribute('src', 'data:image/jpeg;base64,/9j/NEW')
    fireEvent.change(nickname, { target: { value: '  小林 ' } })
    expect(screen.getByText('2/20 · 不填就显示手机号')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    expect(await screen.findByText('资料已保存')).toBeVisible()
    const saved = fetchMock.mock.calls.find(([input]) => String(input) === '/api/account/profile')
    expect(JSON.parse(String(saved?.[1]?.body))).toEqual({ nickname: '小林', avatar: 'data:image/jpeg;base64,/9j/NEW' })
    // 回到个人中心，卡片上就是新的昵称和头像。
    const card = screen.getByRole('button', { name: '编辑资料' })
    expect(card).toHaveTextContent('小林')
    expect(card.querySelector('img')).toHaveAttribute('src', 'data:image/jpeg;base64,/9j/NEW')
  })

  it('sends only what changed, can remove the avatar, and keeps the draft when the server refuses', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/session') return json({ data: { phone: '13900000001', nickname: '小林', avatar: 'data:image/jpeg;base64,/9j/OLD' } })
      if (String(input) === '/api/plan') return json({ data: {} })
      return json({ error: '头像图片无效或太大' }, 400)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()

    // 资料读到之前卡片不能点，免得草稿是空的；读到以后才能打开编辑，草稿里是现在的昵称。
    const card = screen.getByRole('button', { name: '编辑资料' })
    expect(card).toBeDisabled()
    await waitFor(() => expect(card).toBeEnabled())
    expect(card).toHaveTextContent('小林')
    fireEvent.click(card)
    expect(screen.getByRole('textbox', { name: '昵称' })).toHaveValue('小林')
    // 什么都没改，点保存直接回去，不发请求。
    fireEvent.click(screen.getByRole('button', { name: '保存' }))
    expect(screen.getByRole('heading', { name: '个人中心' })).toBeVisible()
    expect(fetchMock.mock.calls.some(([input]) => String(input) === '/api/account/profile')).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: '编辑资料' }))
    fireEvent.click(screen.getByRole('button', { name: '移除头像' }))
    expect(screen.queryByRole('img', { name: '头像预览' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('头像图片无效或太大')
    const sent = fetchMock.mock.calls.find(([input]) => String(input) === '/api/account/profile') as unknown as [string, RequestInit]
    expect(JSON.parse(String(sent[1].body))).toEqual({ avatar: '' })
    expect(screen.getByRole('heading', { name: '编辑资料' })).toBeVisible()
  })

  it('does not offer profile editing to a session that came from the console', async () => {
    vi.stubGlobal('fetch', withProfile(() => json({ data: {} })))
    render(<AccountPanel roleCount={1} account={false} {...handlers} />)
    expect(await screen.findByText('139****0001')).toBeVisible()
    expect(screen.queryByRole('button', { name: '编辑资料' })).not.toBeInTheDocument()
  })

  it('verifies the phone first, then sets the password on the next page, then offers to change it', async () => {
    const fetchMock = withProfile((input, init) => {
      if (String(input) === '/api/account/sms-code') return json({ data: { sent: true } })
      if (String(input) === '/api/account/verify-code') {
        const { code } = JSON.parse(String(init?.body)) as { code: string }
        return code === '123456' ? json({ data: { ok: true } }) : json({ error: '验证码错误' }, 400)
      }
      return json({ data: { ok: true } })
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: /设置密码/ }))
    expect(screen.getByRole('heading', { name: '验证手机号' })).toBeVisible()
    expect(screen.getByText(/139\*\*\*\*0001/)).toBeVisible()
    // 验证码框和“获取验证码”一开始就在同一行，发过之后按钮倒数。
    expect(screen.getByRole('textbox', { name: '密码验证码' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
    expect(await screen.findByRole('button', { name: /秒后重发/ })).toBeDisabled()

    // 验证码不对，留在这一步。
    fireEvent.change(screen.getByRole('textbox', { name: '密码验证码' }), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: '下一步' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('验证码错误')
    expect(screen.queryByLabelText('新密码')).not.toBeInTheDocument()

    fireEvent.change(screen.getByRole('textbox', { name: '密码验证码' }), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: '下一步' }))
    expect(await screen.findByRole('heading', { name: '设置密码' })).toBeVisible()

    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'weak' } })
    fireEvent.click(screen.getByRole('button', { name: '保存密码' }))
    expect(screen.getByRole('alert')).toHaveTextContent('密码需要8 到 32 位')

    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'NewPass123' } })
    fireEvent.click(screen.getByRole('button', { name: '保存密码' }))

    expect(await screen.findByRole('status')).toHaveTextContent('密码已设置')
    const saved = fetchMock.mock.calls.find(([input]) => String(input) === '/api/account/password')
    // 保存时带的是第一步核对过的那个验证码。
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

    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
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
    fireEvent.click(screen.getByRole('button', { name: '获取验证码' }))
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
