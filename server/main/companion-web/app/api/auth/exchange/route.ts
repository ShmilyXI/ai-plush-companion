import { NextResponse } from 'next/server'
import { authCookieName, managerRequest, readManagerPayload } from '@/lib/server-manager'

export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { code?: unknown } | null
  if (!body || typeof body.code !== 'string' || !body.code.trim()) {
    return NextResponse.json({ error: '缺少一次性登录凭据' }, { status: 400 })
  }
  const upstream = await managerRequest('/api/v1/web-sessions/exchange', {
    method: 'POST',
    body: JSON.stringify({ code: body.code.trim() }),
  })
  const payload = await upstream.json().catch(() => ({}))
  if (!upstream.ok || (typeof payload?.code === 'number' && payload.code !== 0)) return NextResponse.json({ error: payload?.msg || '登录凭据无效' }, { status: upstream.ok ? 401 : upstream.status })
  const session = readManagerPayload<{ token?: string; accessToken?: string; expiresAt?: string }>(payload)
  const token = session.token || session.accessToken
  if (!token) return NextResponse.json({ error: '登录响应缺少会话凭据' }, { status: 502 })
  const response = NextResponse.json({ ok: true, expiresAt: session.expiresAt || null })
  response.cookies.set(authCookieName(), token, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: 15 * 60,
  })
  return response
}
