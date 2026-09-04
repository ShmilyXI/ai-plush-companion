import { cookies } from 'next/headers'

const tokenCookie = 'companion_web_token'
const managerBase = () => (process.env.MANAGER_API_BASE_URL || 'http://127.0.0.1:8002/zixuan').replace(/\/$/, '')

export async function managerRequest(path: string, init: RequestInit = {}) {
  const token = (await cookies()).get(tokenCookie)?.value
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  return fetch(`${managerBase()}${path}`, { ...init, headers, cache: 'no-store' })
}

export function authCookieName() { return tokenCookie }

export function readManagerPayload<T>(value: unknown): T {
  const payload = value as { code?: number; msg?: string; data?: T }
  if (typeof payload?.code === 'number' && payload.code !== 0) throw new Error(payload.msg || '管理端请求失败')
  return (payload?.data ?? value) as T
}
