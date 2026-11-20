import { NextResponse } from 'next/server'
import { callManager } from '@/lib/server-manager'

/** 转发客户端事件。统计失败不应该让页面看到错误，所以总是回答成功。 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { events?: unknown } | null
  if (body && Array.isArray(body.events) && body.events.length > 0) {
    await callManager('/app/v1/events', {
      method: 'POST', body: JSON.stringify({ events: body.events.slice(0, 20) }),
    }).catch(() => null)
  }
  return NextResponse.json({ data: { ok: true } })
}
