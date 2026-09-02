import { NextResponse } from 'next/server'
import { managerRequest, readManagerPayload } from '@/lib/server-manager'

function browserSession(value: ReturnType<typeof readManagerPayload>) {
  const session = value as { streamUrl?: string }
  const publicOrigin = process.env.PUBLIC_RUNTIME_WS_ORIGIN?.trim()
  if (!publicOrigin || !session.streamUrl) return value
  try {
    const streamUrl = new URL(session.streamUrl)
    const origin = new URL(publicOrigin)
    streamUrl.protocol = origin.protocol === 'https:' ? 'wss:' : 'ws:'
    streamUrl.host = origin.host
    streamUrl.username = ''
    streamUrl.password = ''
    return { ...session, streamUrl: streamUrl.toString() }
  } catch { return value }
}

export async function POST(request: Request) {
  const body = await request.json().catch(() => null)
  const upstream = await managerRequest('/api/v1/conversations', {
    method: 'POST',
    body: JSON.stringify(body || {}),
  })
  const payload = await upstream.json().catch(() => ({}))
  if (!upstream.ok || (typeof payload?.code === 'number' && payload.code !== 0)) return NextResponse.json({ error: payload?.msg || '会话创建失败' }, { status: upstream.ok ? 401 : upstream.status })
  return NextResponse.json({ data: browserSession(readManagerPayload(payload)) })
}
