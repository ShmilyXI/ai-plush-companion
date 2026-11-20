import { NextResponse } from 'next/server'
import { callManager, clearSession, currentToken } from '@/lib/server-manager'

export async function POST() {
  const token = await currentToken()
  // 只有手机号登录的会话需要通知服务端作废；从管理台带过来的短期会话直接清 Cookie。
  if (token?.startsWith('app_')) await callManager('/app/v1/auth/logout', { method: 'POST' }).catch(() => null)
  await clearSession()
  return NextResponse.json({ data: { ok: true } })
}
