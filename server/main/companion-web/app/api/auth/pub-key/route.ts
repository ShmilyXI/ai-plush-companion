import { NextResponse } from 'next/server'
import { callManagerAnonymous } from '@/lib/server-manager'

/** 加密密码用的 SM2 公钥。只给公钥，不转发公共配置里的其他内容。 */
export async function GET() {
  const result = await callManagerAnonymous<{ sm2PublicKey?: unknown }>('/user/pub-config')
  const publicKey = typeof result.data?.sm2PublicKey === 'string' ? result.data.sm2PublicKey : ''
  if (!result.ok || !publicKey) return NextResponse.json({ error: '暂时无法加密密码，请稍后重试' }, { status: 502 })
  return NextResponse.json({ data: { publicKey } })
}
