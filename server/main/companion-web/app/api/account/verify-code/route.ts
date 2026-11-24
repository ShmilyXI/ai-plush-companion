import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

/** 改密码第一步：核对发到自己手机上的验证码。验证码不作废，下一页保存密码时还要用。 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { code?: unknown } | null
  const code = typeof body?.code === 'string' ? body.code.trim() : ''
  if (!code) return NextResponse.json({ error: '请输入短信里的验证码' }, { status: 400 })
  return respond(await callManager('/app/v1/auth/account/verify-code', {
    method: 'POST', body: JSON.stringify({ code }),
  }), '验证码不对，请再试一次', () => ({ ok: true }))
}
