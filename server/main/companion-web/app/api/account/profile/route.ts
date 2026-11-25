import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

interface Profile { nickname?: string | null; avatar?: string | null }

/**
 * 改自己的昵称和头像。只转发传了的项，空字符串表示清掉。
 * 头像是浏览器里裁好、缩小过的 data URL，格式和大小由 manager-api 再检查一遍。
 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { nickname?: unknown; avatar?: unknown } | null
  const changes: Record<string, string> = {}
  if (typeof body?.nickname === 'string') changes.nickname = body.nickname
  if (typeof body?.avatar === 'string') changes.avatar = body.avatar
  if (!Object.keys(changes).length) return NextResponse.json({ error: '没有要保存的内容' }, { status: 400 })
  return respond(await callManager<Profile>('/app/v1/auth/profile', {
    method: 'POST', body: JSON.stringify(changes),
  }), '资料保存失败', (data) => ({ nickname: data?.nickname || '', avatar: data?.avatar || '' }))
}
