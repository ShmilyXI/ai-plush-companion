import { NextResponse } from 'next/server'
import { fetchManager } from '@/lib/server-manager'

type Context = { params: Promise<{ id: string }> }

const VOICE_ID = /^[A-Za-z0-9_-]{1,64}$/

/**
 * 试听一个音色：manager-api 用这个音色读一句固定的话。浏览器只能选音色，不能指定文本或模型配置。
 * 成功时原样转发音频；失败时 manager-api 返回 JSON，这里转成 { error }。
 */
export async function GET(_request: Request, context: Context) {
  const { id } = await context.params
  if (!VOICE_ID.test(id)) return NextResponse.json({ error: '音色不存在' }, { status: 400 })
  const upstream = await fetchManager(`/api/v1/voices/${encodeURIComponent(id)}/preview`)
  const type = upstream.headers.get('content-type') || ''
  if (upstream.ok && type.startsWith('audio/')) {
    return new Response(upstream.body, {
      headers: { 'Content-Type': type, 'Cache-Control': 'private, max-age=86400' },
    })
  }
  const payload = await upstream.json().catch(() => null) as { code?: unknown; msg?: unknown } | null
  const status = upstream.status === 401 || payload?.code === 401 ? 401 : upstream.ok ? 400 : 502
  const error = typeof payload?.msg === 'string' && payload.msg ? payload.msg : '试听失败，请稍后再试'
  return NextResponse.json({ error }, { status })
}
