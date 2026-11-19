import { beforeEach, describe, expect, it, vi } from 'vitest'

const cookieJar = new Map<string, string>()
const incomingHeaders = new Map<string, string>()

vi.mock('next/headers', () => ({
  cookies: async () => ({
    get: (name: string) => cookieJar.has(name) ? { name, value: cookieJar.get(name)! } : undefined,
    set: (name: string, value: string) => { cookieJar.set(name, value) },
    delete: (name: string) => { cookieJar.delete(name) },
  }),
  headers: async () => ({ get: (name: string) => incomingHeaders.get(name.toLowerCase()) ?? null }),
}))

import { callManager, callManagerAnonymous } from './server-manager'

function managerResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

function authorizationOf(call: unknown[]) {
  return new Headers((call[1] as RequestInit).headers).get('Authorization')
}

describe('server manager client', () => {
  beforeEach(() => {
    cookieJar.clear()
    incomingHeaders.clear()
  })

  it('renews an expired access token once and retries the request', async () => {
    cookieJar.set('companion_web_token', 'app_old')
    cookieJar.set('companion_web_refresh', 'appr_keep')
    const fetchMock = vi.fn()
      // manager-api reports an expired session as HTTP 200 with business code 401
      .mockResolvedValueOnce(managerResponse({ code: 401, msg: '未授权', data: null }))
      .mockResolvedValueOnce(managerResponse({ code: 0, msg: 'success', data: {
        accessToken: 'app_new', expiresIn: 43200, refreshToken: 'appr_keep', refreshExpiresIn: 2000000,
      } }))
      .mockResolvedValueOnce(managerResponse({ code: 0, msg: 'success', data: [{ id: 'agent-a' }] }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await callManager('/api/v1/agents')

    expect(result).toMatchObject({ ok: true, status: 200, data: [{ id: 'agent-a' }] })
    expect(String(fetchMock.mock.calls[1][0])).toContain('/app/v1/auth/refresh')
    expect(authorizationOf(fetchMock.mock.calls[1])).toBeNull()
    expect(authorizationOf(fetchMock.mock.calls[2])).toBe('Bearer app_new')
    expect(cookieJar.get('companion_web_token')).toBe('app_new')
  })

  it('signs the visitor out when the refresh token is no longer accepted', async () => {
    cookieJar.set('companion_web_token', 'app_old')
    cookieJar.set('companion_web_refresh', 'appr_dead')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(managerResponse({ code: 401, msg: '未授权', data: null }))
      .mockResolvedValueOnce(managerResponse({ code: 10208, msg: '刷新令牌无效或已过期', data: null }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await callManager('/api/v1/agents')

    expect(result).toMatchObject({ ok: false, status: 401 })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(cookieJar.size).toBe(0)
  })

  it('does not try to renew a short console session that has no refresh token', async () => {
    cookieJar.set('companion_web_token', 'web_short')
    const fetchMock = vi.fn().mockResolvedValueOnce(managerResponse({ code: 401, msg: '未授权', data: null }))
    vi.stubGlobal('fetch', fetchMock)

    expect(await callManager('/user/info')).toMatchObject({ ok: false, status: 401 })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('passes the visitor address on and reports business errors with their code', async () => {
    incomingHeaders.set('x-forwarded-for', '203.0.113.9, 10.0.0.2')
    const fetchMock = vi.fn().mockResolvedValueOnce(managerResponse({ code: 10210, msg: '首次登录需要填写邀请码', data: null }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await callManagerAnonymous('/app/v1/auth/login-code', { method: 'POST', body: '{}' })

    expect(result).toEqual({ ok: false, status: 400, code: 10210, message: '首次登录需要填写邀请码', data: null })
    const headers = new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers)
    expect(headers.get('X-Forwarded-For')).toBe('203.0.113.9')
    expect(headers.get('Authorization')).toBeNull()
  })
})
