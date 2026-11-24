import { NextResponse } from 'next/server'
import { callManagerAnonymous, storeAppSession, validTokens } from './server-manager'

/** 调 manager-api 的登录或注册接口，成功后把令牌放进 httpOnly Cookie。 */
export async function signIn(path: string, body: Record<string, unknown>, fallback: string) {
  const result = await callManagerAnonymous(path, {
    method: 'POST', headers: { 'X-Device-Label': 'web' }, body: JSON.stringify(body),
  })
  if (!result.ok) {
    return NextResponse.json({ error: result.message || fallback, code: result.code }, { status: result.status })
  }
  if (!validTokens(result.data)) {
    return NextResponse.json({ error: '登录响应缺少会话凭据' }, { status: 502 })
  }
  // 令牌只放在 httpOnly Cookie 里，页面脚本读不到。
  await storeAppSession(result.data)
  return NextResponse.json({ data: { ok: true } })
}
