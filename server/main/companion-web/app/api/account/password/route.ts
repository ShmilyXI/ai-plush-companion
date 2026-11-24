import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

/** 设置或修改密码：发到自己手机上的验证码，加上在浏览器里 SM2 加密过的新密码。 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { code?: unknown; password?: unknown } | null
  const code = typeof body?.code === 'string' ? body.code.trim() : ''
  const password = typeof body?.password === 'string' ? body.password.trim() : ''
  if (!code || !password) return NextResponse.json({ error: '请输入验证码和新密码' }, { status: 400 })
  return respond(await callManager('/app/v1/auth/account/password', {
    method: 'POST', body: JSON.stringify({ code, password }),
  }), '密码设置失败', () => ({ ok: true }))
}
