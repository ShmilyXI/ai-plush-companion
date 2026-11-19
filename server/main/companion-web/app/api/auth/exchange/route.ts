import { NextResponse } from 'next/server'
import { cookies } from 'next/headers'
import { authCookieName, callManagerAnonymous } from '@/lib/server-manager'

/** 用管理台签发的一次性登录码换一个短期网页会话。 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { code?: unknown } | null
  if (!body || typeof body.code !== 'string' || !body.code.trim()) {
    return NextResponse.json({ error: '缺少一次性登录凭据' }, { status: 400 })
  }
  const result = await callManagerAnonymous<{ token?: string; accessToken?: string; expiresAt?: string }>(
    '/api/v1/web-sessions/exchange', { method: 'POST', body: JSON.stringify({ code: body.code.trim() }) })
  if (!result.ok) return NextResponse.json({ error: result.message || '登录凭据无效' }, { status: result.status === 400 ? 401 : result.status })
  const token = result.data?.token || result.data?.accessToken
  if (!token) return NextResponse.json({ error: '登录响应缺少会话凭据' }, { status: 502 })
  const store = await cookies()
  store.set(authCookieName(), token, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: 15 * 60,
  })
  return NextResponse.json({ ok: true, expiresAt: result.data?.expiresAt || null })
}
