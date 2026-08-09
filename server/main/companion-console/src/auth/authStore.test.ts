import { AxiosHeaders } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http, { ApiError, configureAuthBridge, createHttpClient } from '../api/http'
import { encryptLoginPassword } from './crypto'
import { SESSION_STORAGE_KEY, readStoredToken, useAuthStore } from './authStore'

vi.mock('./crypto', () => ({
  AuthProtocolError: class AuthProtocolError extends Error {},
  encryptLoginPassword: vi.fn(() => '04encrypted-password'),
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

describe('auth store', () => {
  beforeEach(() => {
    useAuthStore.getState().clearSession()
    vi.clearAllMocks()
  })

  it('posts login without captcha fields and derives backend permissions', async () => {
    const get = vi.spyOn(http, 'get')
      .mockResolvedValueOnce({ data: { code: 0, data: { sm2PublicKey: 'server-public-key' } } })
      .mockResolvedValueOnce({ data: { code: 0, data: { id: '1', username: 'admin', superAdmin: 1, status: 1 } } })
    const post = vi.spyOn(http, 'post').mockResolvedValue({
      data: { code: 0, data: { token: 'session-token', expire: 43200, clientHash: 'browser' } },
    })

    await useAuthStore.getState().login({
      username: 'admin',
      password: 'secret',
    })

    expect(encryptLoginPassword).toHaveBeenCalledWith('server-public-key', 'secret')
    expect(post).toHaveBeenCalledWith('/user/login', {
      username: 'admin',
      password: '04encrypted-password',
    })
    expect(get).toHaveBeenLastCalledWith('/user/info', {
      headers: { Authorization: 'Bearer session-token' },
    })
    expect(useAuthStore.getState().permissions).toEqual(['sys:role:normal', 'sys:role:superAdmin'])
    expect(useAuthStore.getState().user?.id).toBe('1')
    expect(JSON.parse(localStorage.getItem(SESSION_STORAGE_KEY) ?? '{}')).toEqual({ token: 'session-token' })
  })

  it('ignores malformed or empty persisted tokens', () => {
    localStorage.setItem(SESSION_STORAGE_KEY, '{broken')
    expect(readStoredToken()).toBeNull()

    localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ token: '   ' }))
    expect(readStoredToken()).toBeNull()
  })

  it('hydrates a persisted token through user info before authenticating', async () => {
    let resolveInfo!: (value: unknown) => void
    vi.spyOn(http, 'get').mockImplementation(() => new Promise((resolve) => { resolveInfo = resolve }) as never)
    useAuthStore.getState().setTokenForTest('persisted-token')

    const hydration = useAuthStore.getState().hydrate()

    expect(useAuthStore.getState()).toMatchObject({ status: 'initializing', user: null, permissions: [] })
    resolveInfo({ data: { code: 0, data: { id: '7', username: 'demo', superAdmin: 0, status: 1 } } })
    await hydration
    expect(useAuthStore.getState()).toMatchObject({ status: 'authenticated', user: { id: '7', username: 'demo' } })
  })

  it('clears an invalid persisted token when hydration fails', async () => {
    vi.spyOn(http, 'get').mockRejectedValue(new ApiError(401, '登录已过期'))
    useAuthStore.getState().setTokenForTest('expired-token')

    await useAuthStore.getState().hydrate()

    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', token: null, user: null, permissions: [] })
    expect(localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull()
  })

  it('revokes the server session before clearing an explicit logout', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'session-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })
    let resolveLogout!: (value: unknown) => void
    const post = vi.spyOn(http, 'post').mockImplementation(() => new Promise((resolve) => {
      resolveLogout = resolve
    }) as never)

    const logout = useAuthStore.getState().logout()

    expect(useAuthStore.getState().status).toBe('authenticated')
    resolveLogout({ data: { code: 0, data: null } })
    await logout
    expect(post).toHaveBeenCalledWith('/user/logout')
    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', token: null, user: null })
  })

  it('clears the browser session when remote logout fails', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'session-token',
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })
    vi.spyOn(http, 'post').mockRejectedValue(new Error('network unavailable'))

    await expect(useAuthStore.getState().logout()).resolves.toBeUndefined()

    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', token: null, user: null })
    expect(localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull()
  })

  it('drops administrator permissions as soon as refreshed user info revokes them', async () => {
    useAuthStore.getState().setSessionForTest({
      token: 'admin-token',
      user: { id: '1', username: 'admin', superAdmin: 1, status: 1 },
    })
    vi.spyOn(http, 'get').mockResolvedValue({
      data: { code: 0, data: { id: '1', username: 'admin', superAdmin: 0, status: 1 } },
    })

    await useAuthStore.getState().refreshUser()

    expect(useAuthStore.getState().permissions).toEqual(['sys:role:normal'])
  })

  it('rejects an empty login token with a stable protocol error', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({
      data: { code: 0, data: { sm2PublicKey: 'server-public-key' } },
    })
    vi.spyOn(http, 'post').mockResolvedValue({
      data: { code: 0, data: { token: '  ', expire: 43200, clientHash: 'browser' } },
    })

    await expect(useAuthStore.getState().login({
      username: 'admin', password: 'secret',
    })).rejects.toThrow('登录响应缺少有效令牌')
    expect(useAuthStore.getState().status).toBe('anonymous')
  })

  it('rejects malformed user info instead of granting permissions', async () => {
    useAuthStore.getState().setTokenForTest('session-token')
    vi.spyOn(http, 'get').mockResolvedValue({
      data: { code: 0, data: { id: '', username: '', superAdmin: 1 } },
    })

    await expect(useAuthStore.getState().refreshUser()).rejects.toThrow('用户信息响应格式无效')
    expect(useAuthStore.getState().permissions).toEqual([])
  })

  it('does not let an older delayed user response overwrite a newer login', async () => {
    const oldInfo = deferred<{ data: { code: number, data: object } }>()
    const newInfo = deferred<{ data: { code: number, data: object } }>()
    const get = vi.spyOn(http, 'get').mockImplementation((url, config) => {
      if (url === '/user/pub-config') {
        return Promise.resolve({ data: { code: 0, data: { sm2PublicKey: 'server-public-key' } } }) as never
      }
      const authorization = config?.headers?.Authorization
      if (authorization === 'Bearer old-token') return oldInfo.promise as never
      if (authorization === 'Bearer new-token') return newInfo.promise as never
      throw new Error(`unexpected user info authorization: ${String(authorization)}`)
    })
    vi.spyOn(http, 'post').mockImplementation((_url, body) => {
      const username = (body as { username: string }).username
      return Promise.resolve({
        data: { code: 0, data: { token: `${username}-token`, expire: 43200, clientHash: 'browser' } },
      }) as never
    })

    const oldLogin = useAuthStore.getState().login({ username: 'old', password: 'secret' })
    await vi.waitFor(() => expect(get).toHaveBeenCalledWith('/user/info', {
      headers: { Authorization: 'Bearer old-token' },
    }))
    const newLogin = useAuthStore.getState().login({ username: 'new', password: 'secret' })
    await vi.waitFor(() => expect(get).toHaveBeenCalledWith('/user/info', {
      headers: { Authorization: 'Bearer new-token' },
    }))

    newInfo.resolve({ data: { code: 0, data: { id: '2', username: 'new', superAdmin: 0, status: 1 } } })
    await newLogin
    oldInfo.resolve({ data: { code: 0, data: { id: '1', username: 'old', superAdmin: 1, status: 1 } } })
    await expect(oldLogin).rejects.toThrow('登录流程已取消')

    expect(useAuthStore.getState()).toMatchObject({
      token: 'new-token',
      user: { id: '2', username: 'new' },
      permissions: ['sys:role:normal'],
      status: 'authenticated',
    })
  })

  it('does not publish an old authenticated session while the newer login is pending', async () => {
    const oldInfo = deferred<{ data: { code: number, data: object } }>()
    const newInfo = deferred<{ data: { code: number, data: object } }>()
    const get = vi.spyOn(http, 'get').mockImplementation((url, config) => {
      if (url === '/user/pub-config') {
        return Promise.resolve({ data: { code: 0, data: { sm2PublicKey: 'server-public-key' } } }) as never
      }
      const authorization = config?.headers?.Authorization
      if (authorization === 'Bearer old-token') return oldInfo.promise as never
      if (authorization === 'Bearer new-token') return newInfo.promise as never
      throw new Error(`unexpected user info authorization: ${String(authorization)}`)
    })
    vi.spyOn(http, 'post').mockImplementation((_url, body) => {
      const username = (body as { username: string }).username
      return Promise.resolve({
        data: { code: 0, data: { token: `${username}-token`, expire: 43200, clientHash: 'browser' } },
      }) as never
    })

    const oldLogin = useAuthStore.getState().login({ username: 'old', password: 'secret' })
    await vi.waitFor(() => expect(get).toHaveBeenCalledWith('/user/info', {
      headers: { Authorization: 'Bearer old-token' },
    }))
    const newLogin = useAuthStore.getState().login({ username: 'new', password: 'secret' })
    await vi.waitFor(() => expect(get).toHaveBeenCalledWith('/user/info', {
      headers: { Authorization: 'Bearer new-token' },
    }))

    oldInfo.resolve({ data: { code: 0, data: { id: '1', username: 'old', superAdmin: 1, status: 1 } } })
    await expect(oldLogin).rejects.toThrow('登录流程已取消')
    expect(useAuthStore.getState()).toMatchObject({
      token: null,
      user: null,
      permissions: [],
      status: 'anonymous',
    })
    expect(localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull()

    newInfo.resolve({ data: { code: 0, data: { id: '2', username: 'new', superAdmin: 0, status: 1 } } })
    await newLogin
  })

  it('keeps the newer session when an older login receives a late business 401 through interceptors', async () => {
    const originalAdapter = http.defaults.adapter
    const oldInfoRequested = deferred<void>()
    const releaseOldInfo = deferred<void>()
    http.defaults.adapter = async (config) => {
      if (config.url === '/user/pub-config') {
        return {
          config,
          data: { code: 0, data: { sm2PublicKey: 'server-public-key' } },
          headers: new AxiosHeaders(),
          status: 200,
          statusText: 'OK',
        }
      }
      if (config.url === '/user/login') {
        const username = (JSON.parse(String(config.data)) as { username: string }).username
        return {
          config,
          data: { code: 0, data: { token: `${username}-token`, expire: 43200, clientHash: 'browser' } },
          headers: new AxiosHeaders(),
          status: 200,
          statusText: 'OK',
        }
      }
      if (config.url === '/user/info') {
        const authorization = config.headers.get('Authorization')
        if (authorization === 'Bearer old-token') {
          oldInfoRequested.resolve()
          await releaseOldInfo.promise
          return {
            config,
            data: { code: 401, msg: '旧登录已过期', data: null },
            headers: new AxiosHeaders(),
            status: 200,
            statusText: 'OK',
          }
        }
        if (authorization === 'Bearer new-token') {
          return {
            config,
            data: { code: 0, data: { id: '2', username: 'new', superAdmin: 0, status: 1 } },
            headers: new AxiosHeaders(),
            status: 200,
            statusText: 'OK',
          }
        }
      }
      throw new Error(`unexpected request: ${String(config.method)} ${String(config.url)}`)
    }

    try {
      const oldLogin = useAuthStore.getState().login({ username: 'old', password: 'secret' })
      await oldInfoRequested.promise
      await useAuthStore.getState().login({ username: 'new', password: 'secret' })
      releaseOldInfo.resolve()
      await expect(oldLogin).rejects.toMatchObject({ code: 401 })

      expect(useAuthStore.getState()).toMatchObject({
        token: 'new-token',
        user: { id: '2', username: 'new' },
        permissions: ['sys:role:normal'],
        status: 'authenticated',
      })
    } finally {
      http.defaults.adapter = originalAdapter
    }
  })
})

describe('HTTP client', () => {
  it('sends the bearer token and rejects nonzero API results as typed errors', async () => {
    configureAuthBridge({ getToken: () => 'session-token', onUnauthorized: vi.fn() })
    const client = createHttpClient()
    client.defaults.adapter = async (config) => ({
      config,
      data: { code: 50301, msg: '服务暂不可用', data: null },
      headers: {},
      status: 200,
      statusText: 'OK',
    })

    const error = await client.get('/probe').catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError).toMatchObject({ code: 50301, message: '服务暂不可用' })
    expect(apiError.config?.headers.Authorization).toBe('Bearer session-token')
  })

  it('clears authentication once when the server returns 401', async () => {
    const onUnauthorized = vi.fn()
    configureAuthBridge({ getToken: () => 'expired', onUnauthorized })
    const client = createHttpClient()
    client.defaults.adapter = async (config) => ({
      config,
      data: { code: 401, msg: '登录已过期', data: null },
      headers: {},
      status: 200,
      statusText: 'OK',
    })

    await expect(client.get('/user/info')).rejects.toBeInstanceOf(ApiError)
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })
})
