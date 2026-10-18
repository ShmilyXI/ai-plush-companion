import { NextResponse } from 'next/server'
import { managerRequest, readManagerPayload } from '@/lib/server-manager'

export async function GET(request: Request) {
  const model = new URL(request.url).searchParams.get('ttsModelId') || ''
  if (!model) return NextResponse.json({ data: [] })
  const upstream = await managerRequest(`/api/v1/voices?ttsModelId=${encodeURIComponent(model)}&page=1&limit=100`)
  const payload = await upstream.json().catch(() => ({}))
  if (!upstream.ok || (typeof payload?.code === 'number' && payload.code !== 0)) return NextResponse.json({ error: payload?.msg || '音色加载失败' }, { status: upstream.ok ? 401 : upstream.status })
  const page = readManagerPayload<{ list?: unknown[] }>(payload)
  return NextResponse.json({ data: Array.isArray(page?.list) ? page.list : [] })
}
