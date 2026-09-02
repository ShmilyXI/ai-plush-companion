import { NextResponse } from 'next/server'
import { managerRequest, readManagerPayload } from '@/lib/server-manager'

export async function GET() {
  const upstream = await managerRequest('/user/info')
  const payload = await upstream.json().catch(() => ({}))
  if (!upstream.ok || (typeof payload?.code === 'number' && payload.code !== 0)) {
    return NextResponse.json({ error: '未登录' }, { status: 401 })
  }
  return NextResponse.json({ data: readManagerPayload(payload) })
}
