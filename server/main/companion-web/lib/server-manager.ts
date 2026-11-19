import { cookies, headers } from 'next/headers'

const tokenCookie = 'companion_web_token'
const refreshCookie = 'companion_web_refresh'
const managerBase = () => (process.env.MANAGER_API_BASE_URL || 'http://127.0.0.1:8002/zixuan').replace(/\/$/, '')
const secureCookies = () => process.env.NODE_ENV === 'production'

export interface ManagerResult<T = unknown> {
  ok: boolean
  /** HTTP status to answer the browser with. */
  status: number
  /** manager-api 的业务错误码，成功时为 0。 */
  code: number
  message: string
  data: T | null
}

interface AppTokens {
  accessToken: string
  expiresIn: number
  refreshToken: string
  refreshExpiresIn: number
}

/** 浏览器的来源地址。manager-api 按来源地址限制短信发送频率，不转发的话所有用户会共用本服务的地址。 */
async function clientAddress() {
  const incoming = await headers()
  const forwarded = incoming.get('x-forwarded-for')?.split(',')[0]?.trim()
  return forwarded || incoming.get('x-real-ip')?.trim() || ''
}

async function send(path: string, init: RequestInit, token: string | undefined) {
  const requestHeaders = new Headers(init.headers)
  requestHeaders.set('Accept', 'application/json')
  if (token) requestHeaders.set('Authorization', `Bearer ${token}`)
  if (init.body && !requestHeaders.has('Content-Type')) requestHeaders.set('Content-Type', 'application/json')
  const address = await clientAddress()
  if (address) requestHeaders.set('X-Forwarded-For', address)
  return fetch(`${managerBase()}${path}`, { ...init, headers: requestHeaders, cache: 'no-store' })
}

async function parse<T>(upstream: Response): Promise<ManagerResult<T>> {
  const payload = await upstream.json().catch(() => null) as { code?: unknown; msg?: unknown; data?: T } | null
  const code = typeof payload?.code === 'number' ? payload.code : upstream.ok ? 0 : upstream.status
  const ok = upstream.ok && code === 0
  // manager-api 用 HTTP 200 加业务码 401 表示未登录，这里统一成 401。
  const unauthorized = upstream.status === 401 || code === 401
  return {
    ok,
    status: ok ? 200 : unauthorized ? 401 : upstream.ok ? 400 : upstream.status,
    code,
    message: typeof payload?.msg === 'string' ? payload.msg : '',
    data: ok ? ((payload?.data ?? null) as T | null) : null,
  }
}

export async function storeAppSession(tokens: AppTokens) {
  const store = await cookies()
  store.set(tokenCookie, tokens.accessToken, {
    httpOnly: true, sameSite: 'lax', secure: secureCookies(), path: '/', maxAge: tokens.refreshExpiresIn,
  })
  store.set(refreshCookie, tokens.refreshToken, {
    httpOnly: true, sameSite: 'lax', secure: secureCookies(), path: '/', maxAge: tokens.refreshExpiresIn,
  })
}

export async function clearSession() {
  const store = await cookies()
  store.delete(tokenCookie)
  store.delete(refreshCookie)
}

export async function currentToken() {
  return (await cookies()).get(tokenCookie)?.value
}

function validTokens(value: unknown): value is AppTokens {
  const tokens = value as Partial<AppTokens> | null
  return Boolean(tokens && typeof tokens.accessToken === 'string' && tokens.accessToken
    && typeof tokens.refreshToken === 'string' && tokens.refreshToken
    && typeof tokens.expiresIn === 'number' && typeof tokens.refreshExpiresIn === 'number')
}

/** 用刷新令牌换新的访问令牌。换不到时清掉会话，让用户重新登录。 */
async function refreshSession() {
  const refreshToken = (await cookies()).get(refreshCookie)?.value
  if (!refreshToken) return null
  const result = await parse<AppTokens>(await send('/app/v1/auth/refresh', {
    method: 'POST', body: JSON.stringify({ refreshToken }),
  }, undefined))
  if (!result.ok || !validTokens(result.data)) {
    await clearSession()
    return null
  }
  await storeAppSession(result.data)
  return result.data.accessToken
}

/**
 * 以当前登录身份请求 manager-api。访问令牌过期时自动用刷新令牌续期并重试一次，
 * 所以只要 30 天内用过，用户就不会被要求重新登录。
 */
export async function callManager<T = unknown>(path: string, init: RequestInit = {}): Promise<ManagerResult<T>> {
  const token = await currentToken()
  const first = await parse<T>(await send(path, init, token))
  if (first.status !== 401) return first
  const renewed = await refreshSession()
  if (!renewed) return first
  return parse<T>(await send(path, init, renewed))
}

/** 不带登录身份的请求，用于发验证码和登录。 */
export async function callManagerAnonymous<T = unknown>(path: string, init: RequestInit = {}) {
  return parse<T>(await send(path, init, undefined))
}

export function authCookieName() { return tokenCookie }

export { validTokens }
