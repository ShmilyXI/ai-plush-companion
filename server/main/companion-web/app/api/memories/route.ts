import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

const base = (agentId: string) => `/api/v1/conversations/threads/${encodeURIComponent(agentId)}/memories`

function agentOf(request: Request) {
  return new URL(request.url).searchParams.get('agentId') || ''
}

/** 一个角色记得的关于用户的事，从早到晚。 */
export async function GET(request: Request) {
  const agentId = agentOf(request)
  if (!agentId) return NextResponse.json({ error: '缺少角色' }, { status: 400 })
  return respond(await callManager<unknown[]>(base(agentId)), '记忆加载失败', (items) => Array.isArray(items) ? items : [])
}

/** 用户自己写一件事让角色记住。 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { agentId?: unknown; content?: unknown } | null
  const agentId = typeof body?.agentId === 'string' ? body.agentId : ''
  const content = typeof body?.content === 'string' ? body.content.trim() : ''
  if (!agentId || !content) return NextResponse.json({ error: '请写下要它记住的事' }, { status: 400 })
  if (content.length > 200) return NextResponse.json({ error: '一条最多 200 个字' }, { status: 400 })
  return respond(await callManager(base(agentId), { method: 'POST', body: JSON.stringify({ content }) }), '没有记下来，请重试')
}

/** 忘掉一条（带 id），或者忘掉全部（不带 id）。 */
export async function DELETE(request: Request) {
  const agentId = agentOf(request)
  const id = new URL(request.url).searchParams.get('id')
  if (!agentId) return NextResponse.json({ error: '缺少角色' }, { status: 400 })
  if (id !== null && !/^\d+$/.test(id)) return NextResponse.json({ error: '这条记忆不存在' }, { status: 400 })
  const path = id === null ? base(agentId) : `${base(agentId)}/${id}`
  return respond(await callManager(path, { method: 'DELETE' }), '删除失败，请重试')
}
