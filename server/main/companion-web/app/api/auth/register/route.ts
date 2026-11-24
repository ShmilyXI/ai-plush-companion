import { NextResponse } from 'next/server'
import { signIn } from '@/lib/sign-in'

/** 注册：手机号、短信验证码、加密后的密码、邀请码，以及同意的协议版本。成功后直接登录。 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as Record<string, unknown> | null
  const text = (key: string) => typeof body?.[key] === 'string' ? (body[key] as string).trim() : ''
  if (!text('phone') || !text('code')) return NextResponse.json({ error: '请输入手机号和验证码' }, { status: 400 })
  if (!text('password')) return NextResponse.json({ error: '请设置密码' }, { status: 400 })
  if (!text('consentVersion')) return NextResponse.json({ error: '请先阅读并同意用户协议和隐私政策' }, { status: 400 })
  return signIn('/app/v1/auth/register', {
    phone: text('phone'), code: text('code'), password: text('password'), consentVersion: text('consentVersion'),
    ...(text('inviteCode') ? { inviteCode: text('inviteCode') } : {}),
  }, '注册失败')
}
