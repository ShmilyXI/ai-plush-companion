import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManagerAnonymous } from '@/lib/server-manager'

export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { phone?: unknown } | null
  if (!body || typeof body.phone !== 'string' || !body.phone.trim()) {
    return NextResponse.json({ error: '请输入手机号' }, { status: 400 })
  }
  const result = await callManagerAnonymous('/app/v1/auth/sms-code', {
    method: 'POST', body: JSON.stringify({ phone: body.phone.trim() }),
  })
  return respond(result, '验证码发送失败', () => ({ sent: true }))
}
