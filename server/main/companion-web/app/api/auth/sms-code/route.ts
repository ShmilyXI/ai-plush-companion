import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManagerAnonymous } from '@/lib/server-manager'

export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { phone?: unknown; captchaId?: unknown; captcha?: unknown } | null
  if (!body || typeof body.phone !== 'string' || !body.phone.trim()) {
    return NextResponse.json({ error: '请输入手机号' }, { status: 400 })
  }
  // 发短信前要先答对图形验证码，防止有人用脚本批量刷短信。
  if (typeof body.captchaId !== 'string' || !body.captchaId || typeof body.captcha !== 'string' || !body.captcha.trim()) {
    return NextResponse.json({ error: '请输入图片里的字符' }, { status: 400 })
  }
  const result = await callManagerAnonymous('/app/v1/auth/sms-code', {
    method: 'POST', body: JSON.stringify({ phone: body.phone.trim(), captchaId: body.captchaId, captcha: body.captcha.trim() }),
  })
  return respond(result, '验证码发送失败', () => ({ sent: true }))
}
