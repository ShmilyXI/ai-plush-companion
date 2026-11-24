import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

/**
 * 当前用户和一个角色的对话按天汇总，从最近的一天往前。
 * tz 是和 UTC 相差的分钟数（东八区是 480），日期按用户这边的时区算。
 */
export async function GET(request: Request) {
  const params = new URL(request.url).searchParams
  const agentId = params.get('agentId') || ''
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(agentId)) return NextResponse.json({ data: [] })
  const tz = params.get('tz') || ''
  const offset = /^-?\d{1,4}$/.test(tz) ? tz : '480'
  const result = await callManager<unknown[]>(`/api/v1/conversations/threads/${agentId}/days?tz=${offset}`)
  return respond(result, '对话记录加载失败', (items) => Array.isArray(items) ? items : [])
}
