import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { templateId?: unknown; name?: unknown } | null
  if (!body || typeof body.templateId !== 'string' || !body.templateId
    || typeof body.name !== 'string' || !body.name.trim()) {
    return NextResponse.json({ error: '请选择一个角色' }, { status: 400 })
  }
  const result = await callManager<string>('/companion/profiles', {
    method: 'POST', body: JSON.stringify({ templateId: body.templateId, name: body.name.trim() }),
  })
  return respond(result, '角色创建失败', (id) => ({ id }))
}
