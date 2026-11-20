import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

export async function GET(request: Request) {
  const model = new URL(request.url).searchParams.get('ttsModelId') || ''
  if (!model) return NextResponse.json({ data: [] })
  const result = await callManager<{ list?: unknown[] }>(
    `/api/v1/voices?ttsModelId=${encodeURIComponent(model)}&page=1&limit=100`)
  return respond(result, '音色加载失败', (page) => Array.isArray(page?.list) ? page.list : [])
}
