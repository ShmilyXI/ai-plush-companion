import { NextResponse } from 'next/server'
import { callManager, currentToken } from '@/lib/server-manager'

export async function GET() {
  const token = await currentToken()
  // 从管理台带过来的短期会话（web_ 开头）只能读 /user/info；手机号登录的会话读自己的资料接口。
  const consoleSession = Boolean(token?.startsWith('web_'))
  const result = await callManager<Record<string, unknown>>(consoleSession ? '/user/info' : '/app/v1/auth/profile')
  if (!result.ok) return NextResponse.json({ error: '未登录' }, { status: 401 })
  return NextResponse.json({ data: { ...(result.data ?? {}), sessionKind: consoleSession ? 'console' : 'app' } })
}
