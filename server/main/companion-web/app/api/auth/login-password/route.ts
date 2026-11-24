import { NextResponse } from 'next/server'
import { signIn } from '@/lib/sign-in'

/** 手机号加密码登录。密码在浏览器里已经用 SM2 加密过。 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { phone?: unknown; password?: unknown } | null
  const phone = typeof body?.phone === 'string' ? body.phone.trim() : ''
  const password = typeof body?.password === 'string' ? body.password.trim() : ''
  if (!phone || !password) return NextResponse.json({ error: '请输入手机号和密码' }, { status: 400 })
  return signIn('/app/v1/auth/login-password', { phone, password }, '登录失败')
}
