import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { PageContainer } from '@ant-design/pro-components'
import { Input } from 'antd'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '../auth/authStore'
import { AppShell, MobileMenuActionRegistration } from './AppShell'
import { RequireAdmin, RequireAuth, RequireRoutePermission } from './router'

function renderShell(initialEntry = '/dashboard') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="dashboard" element={<><div>控制台内容</div><Input aria-label="主题输入框" /></>} />
          <Route path="devices" element={<div>设备列表内容</div>} />
          <Route path="devices/:id" element={<PageContainer title="设备详情内容" />} />
          <Route path="account" element={<div>账号资料页面</div>} />
          <Route path="*" element={<Outlet />} />
        </Route>
        <Route path="login" element={<div>登录页面</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('AppShell permission navigation', () => {
  beforeEach(() => {
    delete process.env.USE_MEDIA
    useAuthStore.getState().clearSession()
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
  })

  it('shows administrator navigation for a super administrator', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'admin-token',
      user: { id: '1', username: 'admin', superAdmin: 1, status: 1 },
    })

    renderShell()

    expect(await screen.findByText('工作台')).toBeInTheDocument()
    expect(screen.getByText('AI 能力')).toBeInTheDocument()
    expect(screen.getByText('平台管理')).toBeInTheDocument()
    expect(screen.getByText('设备运营')).toBeInTheDocument()
    expect(screen.getByText('模型管理')).toBeInTheDocument()
    expect(screen.getByText('控制台内容')).toBeInTheDocument()
  })

  it('keeps content form controls on the light theme', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })

    renderShell()

    const input = await screen.findByRole('textbox', { name: '主题输入框' })
    expect(getComputedStyle(input).borderColor).toBe('rgb(217, 217, 217)')
  })

  it('hides administrator navigation from an ordinary user', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })

    renderShell()

    expect(await screen.findByText('工作台')).toBeInTheDocument()
    expect(screen.getByText('声音管理')).toBeInTheDocument()
    expect(screen.queryByText('平台管理')).not.toBeInTheDocument()
    expect(screen.queryByText('模型管理')).not.toBeInTheDocument()
    expect(screen.getByText('我的设备')).toBeInTheDocument()
  })

  it('redirects an ordinary user away from administrator routes', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })

    render(
      <MemoryRouter initialEntries={['/admin/users']}>
        <Routes>
          <Route path="admin" element={<RequireAdmin />}>
            <Route path="users" element={<div>用户管理页面</div>} />
          </Route>
          <Route path="dashboard" element={<div>普通用户首页</div>} />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('普通用户首页')).toBeInTheDocument()
    expect(screen.queryByText('用户管理页面')).not.toBeInTheDocument()
  })

  it('reads a protected route permission from navigation metadata', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })

    render(
      <MemoryRouter initialEntries={['/admin/models']}>
        <Routes>
          <Route path="admin/models" element={<RequireRoutePermission routeKey="models" />}>
            <Route index element={<div>模型管理页面</div>} />
          </Route>
          <Route path="dashboard" element={<div>普通用户首页</div>} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('普通用户首页')).toBeInTheDocument()
    expect(screen.queryByText('模型管理页面')).not.toBeInTheDocument()
  })

  it('does not render a protected route while token hydration is initializing', () => {
    useAuthStore.getState().setTokenForTest('persisted-token')
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route element={<RequireAuth />}>
            <Route path="dashboard" element={<div>受保护内容</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )

    expect(screen.queryByText('受保护内容')).not.toBeInTheDocument()
    expect(screen.getByLabelText('正在验证登录状态')).toBeInTheDocument()
  })

  it('keeps the devices menu selected on a device detail route', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })

    renderShell('/devices/device-a')

    await screen.findByText('我的设备')
    const devicesMenuItem = document.querySelector<HTMLElement>('[data-menu-id$="-/devices"]')
    expect(devicesMenuItem).toHaveClass('ant-menu-item-selected')
  })

  it('keeps the detail pathname available to breadcrumb generation', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })

    renderShell('/devices/device-a')

    expect(await screen.findByText('设备详情内容')).toBeInTheDocument()
    expect(screen.getByText('设备详情')).toBeInTheDocument()
  })

  it('keeps menu items free of nested interactive controls', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })

    renderShell()

    const devicesMenuItem = (await screen.findByText('我的设备')).closest('[role="menuitem"]')
    expect(devicesMenuItem).toBeInTheDocument()
    expect(devicesMenuItem?.querySelector('a, button, input, select, textarea, [tabindex]:not([tabindex="-1"])')).toBeNull()
  })

  it('cleans committed mobile menu actions without deleting newer registrations', () => {
    const actions = new Map<string, () => void>()
    const collapse = vi.fn()
    const replacement = vi.fn()
    const { rerender, unmount } = render(
      <MobileMenuActionRegistration actions={actions} path="/admin/models" isMobile onCollapse={collapse}>
        <span>模型管理</span>
      </MobileMenuActionRegistration>,
    )

    expect(actions.get('/admin/models')).toBe(collapse)

    rerender(
      <MobileMenuActionRegistration actions={actions} path="/admin/models" isMobile={false} onCollapse={collapse}>
        <span>模型管理</span>
      </MobileMenuActionRegistration>,
    )
    expect(actions.has('/admin/models')).toBe(false)

    rerender(
      <MobileMenuActionRegistration actions={actions} path="/admin/models" isMobile onCollapse={collapse}>
        <span>模型管理</span>
      </MobileMenuActionRegistration>,
    )
    actions.set('/admin/models', replacement)
    unmount()

    expect(actions.get('/admin/models')).toBe(replacement)
  })

  it('navigates when keyboard focus reaches a menu item and Enter is pressed', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })
    vi.spyOn(KeyboardEvent.prototype, 'keyCode', 'get').mockImplementation(function (this: KeyboardEvent) {
      return this.key === 'Enter' ? 13 : 0
    })
    const user = userEvent.setup()
    renderShell()

    const devicesMenuItem = (await screen.findByText('我的设备')).closest('[role="menuitem"]') as HTMLElement
    devicesMenuItem.focus()
    expect(document.activeElement).toBe(devicesMenuItem)
    await user.keyboard('{Enter}')

    expect(await screen.findByText('设备列表内容')).toBeInTheDocument()
  })

  it('updates permission navigation while the shell remains mounted', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })
    renderShell()
    expect(await screen.findByText('声音管理')).toBeInTheDocument()
    expect(screen.queryByText('平台管理')).not.toBeInTheDocument()

    act(() => useAuthStore.getState().setSessionForTest({
      token: 'admin-token',
      user: { id: '1', username: 'admin', superAdmin: 1, status: 1 },
    }))
    expect(await screen.findByText('平台管理')).toBeInTheDocument()
    expect(screen.getByText('模型管理')).toBeInTheDocument()

    act(() => useAuthStore.getState().setSessionForTest({
      token: 'user-token-2',
      user: { id: '8', username: 'demo-2', superAdmin: 0, status: 1 },
    }))
    await waitFor(() => expect(screen.queryByText('平台管理')).not.toBeInTheDocument())
    expect(screen.queryByText('模型管理')).not.toBeInTheDocument()
  })

  it('closes the responsive navigation after a menu link is clicked', async () => {
    process.env.USE_MEDIA = 'xs'
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: query === '(max-width: 575px)',
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })
    const user = userEvent.setup()
    renderShell()

    const menuTrigger = await waitFor(() => {
      const trigger = document.querySelector<HTMLElement>('.ant-pro-global-header-collapsed-button')
      expect(trigger).toBeInTheDocument()
      return trigger!
    })
    await user.click(menuTrigger)
    expect(await screen.findByRole('dialog')).toBeInTheDocument()

    const devicesMenuItem = (await screen.findByText('我的设备')).closest('[role="menuitem"]') as HTMLElement
    await user.click(devicesMenuItem)

    expect(await screen.findByText('设备列表内容')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('opens the account page from the user menu', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })
    const user = userEvent.setup()
    renderShell()

    await user.click(screen.getByText('demo'))
    await user.click(await screen.findByText('账号资料'))

    expect(await screen.findByText('账号资料页面')).toBeInTheDocument()
  })

  it('logs out once and replaces the current route with login', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })
    const logout = vi.spyOn(useAuthStore.getState(), 'logout').mockResolvedValue()
    const user = userEvent.setup()
    renderShell()

    await user.click(screen.getByText('demo'))
    fireEvent.click(await screen.findByText('退出登录'))
    fireEvent.click(screen.getByText('退出登录'))

    expect(await screen.findByText('登录页面')).toBeInTheDocument()
    expect(logout).toHaveBeenCalledTimes(1)
  })
})
