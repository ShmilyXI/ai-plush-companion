import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

/**
 * 浏览器该连哪个地址去找对话服务。
 *
 * 页面经公网域名打开时（域名列在 PUBLIC_SITE_HOSTS 里），对话服务和页面在同一个域名下，
 * 由前面的反向代理按路径转过去，所以直接用这个域名加 wss。其他情况（本机开发）用 PUBLIC_RUNTIME_WS_ORIGIN。
 * 这样同一个服务可以同时给本机和公网用。
 */
function runtimeOrigin(request: Request) {
  const host = (request.headers.get('x-forwarded-host') || request.headers.get('host') || '').split(',')[0].trim().toLowerCase()
  const publicHosts = (process.env.PUBLIC_SITE_HOSTS || '').split(',').map((item) => item.trim().toLowerCase()).filter(Boolean)
  if (host && publicHosts.includes(host)) return `https://${host}`
  return process.env.PUBLIC_RUNTIME_WS_ORIGIN?.trim() || ''
}

function browserSession(value: unknown, publicOrigin: string) {
  const session = value as { streamUrl?: string } | null
  if (!publicOrigin || !session?.streamUrl) return value
  try {
    const streamUrl = new URL(session.streamUrl)
    const origin = new URL(publicOrigin)
    streamUrl.protocol = origin.protocol === 'https:' || origin.protocol === 'wss:' ? 'wss:' : 'ws:'
    // 主机名和端口分开设：只设 host 而新地址不带端口时，原来的端口（8003）会留下来。
    streamUrl.hostname = origin.hostname
    streamUrl.port = origin.port
    streamUrl.username = ''
    streamUrl.password = ''
    return { ...session, streamUrl: streamUrl.toString() }
  } catch { return value }
}

export async function POST(request: Request) {
  const body = await request.json().catch(() => null)
  const result = await callManager('/api/v1/conversations', { method: 'POST', body: JSON.stringify(body || {}) })
  const origin = runtimeOrigin(request)
  return respond(result, '会话创建失败', (session) => browserSession(session, origin))
}
