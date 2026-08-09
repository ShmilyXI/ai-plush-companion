import { render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '../auth/authStore'
import { router } from './router'

vi.mock('../pages/DashboardPage', () => ({ DashboardPage: () => <div>首页懒加载页</div> }))
vi.mock('../pages/profiles/ProfileListPage', () => ({ ProfileListPage: () => <div>陪伴角色懒加载页</div> }))
vi.mock('../pages/models/ModelManagementPage', () => ({ ModelManagementPage: () => <div>模型管理懒加载页</div> }))
vi.mock('../pages/voices/TimbreManagementPage', () => ({ TimbreManagementPage: () => <div>音色管理懒加载页</div> }))
vi.mock('../pages/voices/VoiceResourcePage', () => ({ VoiceResourcePage: () => <div>音色资源懒加载页</div> }))
vi.mock('../pages/voices/VoiceClonePage', () => ({ VoiceClonePage: () => <div>音色克隆懒加载页</div> }))

const NativeRequest = globalThis.Request

class RouterTestRequest {
  readonly url: string
  readonly method: string
  readonly signal: AbortSignal
  readonly headers: Headers

  constructor(input: string | URL | { url: string }, init: RequestInit = {}) {
    this.url = typeof input === 'string' || input instanceof URL ? input.toString() : input.url
    this.method = init.method ?? 'GET'
    this.signal = init.signal ?? new AbortController().signal
    this.headers = new Headers(init.headers)
  }
}

function setUser(superAdmin: 0 | 1) {
  useAuthStore.getState().setSessionForTest({
    token: superAdmin ? 'admin-token' : 'user-token',
    user: { id: superAdmin ? '1' : '7', username: superAdmin ? 'admin' : 'demo', superAdmin, status: 1 },
  })
}

function renderProductionRoute(path: string, superAdmin: 0 | 1) {
  setUser(superAdmin)
  const memoryRouter = createMemoryRouter(router.routes, { initialEntries: [path] })
  const view = render(<RouterProvider router={memoryRouter} />)
  return { ...view, memoryRouter }
}

describe('xiaozhi model and voice production routes', () => {
  beforeAll(() => {
    vi.stubGlobal('Request', RouterTestRequest)
  })

  afterAll(() => {
    vi.stubGlobal('Request', NativeRequest)
  })

  beforeEach(() => {
    useAuthStore.getState().clearSession()
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
    }))
  })

  it.each([
    ['/admin/models', '模型管理懒加载页'],
    ['/admin/voices?ttsModelId=tts-1', '音色管理懒加载页'],
    ['/admin/voice-resources', '音色资源懒加载页'],
  ])('renders the super administrator page configured at %s', async (path, content) => {
    renderProductionRoute(path, 1)
    expect(await screen.findByText(content, undefined, { timeout: 5_000 })).toBeInTheDocument()
  })

  it.each([
    ['/admin/models', '模型管理懒加载页'],
    ['/admin/voices', '音色管理懒加载页'],
    ['/admin/voice-resources', '音色资源懒加载页'],
  ])('redirects an ordinary user away from the production route %s', async (path, protectedContent) => {
    const { memoryRouter } = renderProductionRoute(path, 0)

    await waitFor(() => expect(memoryRouter.state.location.pathname).toBe('/dashboard'))
    expect(await screen.findByText('首页懒加载页', undefined, { timeout: 5_000 })).toBeInTheDocument()
    expect(screen.queryByText(protectedContent)).not.toBeInTheDocument()
  })

  it.each([0, 1] as const)('allows role %s into the production voice clone route', async (superAdmin) => {
    const { memoryRouter } = renderProductionRoute('/admin/voice-clones', superAdmin)

    expect(await screen.findByText('音色克隆懒加载页', undefined, { timeout: 5_000 })).toBeInTheDocument()
    expect(memoryRouter.state.location.pathname).toBe('/admin/voice-clones')
  })

  it.each([
    [1, '/admin/models', '模型管理懒加载页'],
    [0, '/profiles', '陪伴角色懒加载页'],
  ] as const)('routes the production legacy models path for role %s', async (superAdmin, destination, content) => {
    const { memoryRouter } = renderProductionRoute('/models', superAdmin)

    await waitFor(() => expect(memoryRouter.state.location.pathname).toBe(destination))
    expect(await screen.findByText(content, undefined, { timeout: 5_000 })).toBeInTheDocument()
  })
})
