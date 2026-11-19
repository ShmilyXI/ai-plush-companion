import { NextResponse } from 'next/server'
import { callManagerAnonymous, storeAppSession, validTokens } from '@/lib/server-manager'

export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { phone?: unknown; code?: unknown; inviteCode?: unknown } | null
  if (!body || typeof body.phone !== 'string' || !body.phone.trim()
    || typeof body.code !== 'string' || !body.code.trim()) {
    return NextResponse.json({ error: '请输入手机号和验证码' }, { status: 400 })
  }
  const inviteCode = typeof body.inviteCode === 'string' ? body.inviteCode.trim() : ''
  const result = await callManagerAnonymous('/app/v1/auth/login-code', {
    method: 'POST',
    headers: { 'X-Device-Label': 'web' },
    body: JSON.stringify({ phone: body.phone.trim(), code: body.code.trim(), ...(inviteCode ? { inviteCode } : {}) }),
  })
  if (!result.ok) {
    return NextResponse.json({ error: result.message || '登录失败', code: result.code }, { status: result.status })
  }
  if (!validTokens(result.data)) {
    return NextResponse.json({ error: '登录响应缺少会话凭据' }, { status: 502 })
  }
  // 令牌只放在 httpOnly Cookie 里，页面脚本读不到。
  await storeAppSession(result.data)
  return NextResponse.json({ data: { ok: true } })
}
