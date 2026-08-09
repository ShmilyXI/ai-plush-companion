import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '../auth/authStore'
import { AppShell } from './AppShell'
import { RequireAdmin, RequireAuth } from './router'

function renderShell(initialEntry = '/dashboard') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="dashboard" element={<div>控制台内容</div>} />
          <Route path="devices/:id" element={<div>设备详情内容</div>} />
          <Route path="*" element={<Outlet />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('AppShell permission navigation', () => {
  beforeEach(() => {
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

    expect(await screen.findByText('用户管理')).toBeInTheDocument()
    expect(screen.getByText('系统设置')).toBeInTheDocument()
    expect(screen.getByText('模型管理')).toBeInTheDocument()
    expect(screen.getByText('音色管理')).toBeInTheDocument()
    expect(screen.getByText('音色资源')).toBeInTheDocument()
    expect(screen.getByText('音色克隆')).toBeInTheDocument()
    expect(screen.getByText('控制台内容')).toBeInTheDocument()
  })

  it('hides administrator navigation from an ordinary user', () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })

    renderShell()

    expect(screen.queryByText('用户管理')).not.toBeInTheDocument()
    expect(screen.queryByText('系统设置')).not.toBeInTheDocument()
    expect(screen.queryByText('模型管理')).not.toBeInTheDocument()
    expect(screen.queryByText('音色管理')).not.toBeInTheDocument()
    expect(screen.queryByText('音色资源')).not.toBeInTheDocument()
    expect(screen.getByText('音色克隆')).toBeInTheDocument()
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

  it('uses an overlay drawer instead of a flex sider on a 320px viewport', async () => {
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: query === '(max-width: 991px)',
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
    renderShell()

    expect(document.querySelector('aside')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '打开导航' }))
    expect(await screen.findByRole('dialog')).toHaveClass('mobile-nav-drawer')
    expect(document.querySelector('.ant-drawer-mask')).toBeInTheDocument()
  })

  it('keeps the devices menu selected on a device detail route', () => {
    useAuthStore.getState().setSessionForTest({
      token: 'user-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })

    renderShell('/devices/device-a')

    expect(screen.getByText('我的设备').closest('.ant-menu-item')).toHaveClass('ant-menu-item-selected')
  })

  it('keeps the timbre menu selected when the route has a model query', () => {
    useAuthStore.getState().setSessionForTest({
      token: 'admin-token',
      user: { id: '1', username: 'admin', superAdmin: 1, status: 1 },
    })

    renderShell('/admin/voices?ttsModelId=tts-1')

    expect(screen.getByText('音色管理').closest('.ant-menu-item')).toHaveClass('ant-menu-item-selected')
  })
})
