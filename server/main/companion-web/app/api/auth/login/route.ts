import { NextResponse } from 'next/server'
import { signIn } from '@/lib/sign-in'

export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as {
    phone?: unknown; code?: unknown; inviteCode?: unknown; consentVersion?: unknown
  } | null
  if (!body || typeof body.phone !== 'string' || !body.phone.trim()
    || typeof body.code !== 'string' || !body.code.trim()) {
    return NextResponse.json({ error: '请输入手机号和验证码' }, { status: 400 })
  }
  const inviteCode = typeof body.inviteCode === 'string' ? body.inviteCode.trim() : ''
  // 登录页要求先勾选同意才能提交。这里再把同意的协议版本交给服务端留档。
  const consentVersion = typeof body.consentVersion === 'string' ? body.consentVersion.trim() : ''
  if (!consentVersion) {
    return NextResponse.json({ error: '请先阅读并同意用户协议和隐私政策' }, { status: 400 })
  }
  return signIn('/app/v1/auth/login-code', {
    phone: body.phone.trim(), code: body.code.trim(), consentVersion, ...(inviteCode ? { inviteCode } : {}),
  }, '登录失败')
}
