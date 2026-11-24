import { NextResponse } from 'next/server'
import { fetchManagerAnonymous } from '@/lib/server-manager'

/**
 * 图形验证码的图片。id 由登录页生成，发短信时连同图片上的字符一起交回来核对。
 * 图片看一次就作废，所以不缓存。
 */
export async function GET(request: Request) {
  const id = new URL(request.url).searchParams.get('id') || ''
  if (!/^[A-Za-z0-9-]{8,64}$/.test(id)) return NextResponse.json({ error: '缺少图形验证码编号' }, { status: 400 })
  const upstream = await fetchManagerAnonymous(`/user/captcha?uuid=${encodeURIComponent(id)}`).catch(() => null)
  const type = upstream?.headers.get('content-type') || ''
  if (!upstream?.ok || !type.startsWith('image/')) {
    return NextResponse.json({ error: '图形验证码加载失败' }, { status: 502 })
  }
  return new NextResponse(await upstream.arrayBuffer(), {
    headers: { 'Content-Type': type, 'Cache-Control': 'no-store' },
  })
}
