import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

function agentIdOf(request: Request) {
  const value = new URL(request.url).searchParams.get('agentId') || ''
  return /^[A-Za-z0-9_-]{1,64}$/.test(value) ? value : ''
}

/**
 * 当前用户和一个角色最近的对话，按时间从早到晚。
 * 带 before（毫秒时间戳）时读的是更早的一页，往上翻对话时用。
 */
export async function GET(request: Request) {
  const agentId = agentIdOf(request)
  if (!agentId) return NextResponse.json({ data: [] })
  const before = new URL(request.url).searchParams.get('before') || ''
  const query = /^\d{1,15}$/.test(before) ? `?limit=30&before=${before}` : '?limit=30'
  const result = await callManager<unknown[]>(`/api/v1/conversations/threads/${agentId}${query}`)
  return respond(result, '对话记录加载失败', (items) => Array.isArray(items) ? items : [])
}

/** 删除当前用户和一个角色的全部对话记录。 */
export async function DELETE(request: Request) {
  const agentId = agentIdOf(request)
  if (!agentId) return NextResponse.json({ error: '缺少角色' }, { status: 400 })
  return respond(await callManager(`/api/v1/conversations/threads/${agentId}`, { method: 'DELETE' }), '对话记录删除失败')
}
