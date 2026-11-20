import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

function browserSession(value: unknown) {
  const session = value as { streamUrl?: string } | null
  const publicOrigin = process.env.PUBLIC_RUNTIME_WS_ORIGIN?.trim()
  if (!publicOrigin || !session?.streamUrl) return value
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
  const result = await callManager('/api/v1/conversations', { method: 'POST', body: JSON.stringify(body || {}) })
  return respond(result, '会话创建失败', browserSession)
}
