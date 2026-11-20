import { NextResponse } from 'next/server'
import { callManager, clearSession } from '@/lib/server-manager'

/** 注销当前账号。成功后清掉本机的登录状态。 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { code?: unknown } | null
  if (!body || typeof body.code !== 'string' || !body.code.trim()) {
    return NextResponse.json({ error: '请输入短信验证码' }, { status: 400 })
  }
  const result = await callManager('/app/v1/auth/account/delete', {
    method: 'POST', body: JSON.stringify({ code: body.code.trim() }),
  })
  if (!result.ok) {
    return NextResponse.json({ error: result.message || '注销失败', code: result.code }, { status: result.status })
  }
  await clearSession()
  return NextResponse.json({ data: { ok: true } })
}
