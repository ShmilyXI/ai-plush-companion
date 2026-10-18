import { NextResponse } from 'next/server'
import { managerRequest, readManagerPayload } from '@/lib/server-manager'

export async function GET() {
  const upstream = await managerRequest('/api/v1/agents')
  const payload = await upstream.json().catch(() => ({}))
  if (!upstream.ok || (typeof payload?.code === 'number' && payload.code !== 0)) return NextResponse.json({ error: payload?.msg || '角色加载失败' }, { status: upstream.ok ? 401 : upstream.status })
  return NextResponse.json({ data: readManagerPayload(payload) })
}
