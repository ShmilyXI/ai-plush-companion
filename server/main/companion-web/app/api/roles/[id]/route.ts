import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

interface Profile {
  id?: string; name?: string; userAddress?: string | null; personality?: string | null; systemPrompt?: string | null
  templateId?: string | null; ttsModelId?: string | null; ttsVoiceId?: string | null
}
type Context = { params: Promise<{ id: string }> }

const path = (id: string) => `/companion/profiles/${encodeURIComponent(id)}`

/** 角色的设置。只给出网页端能改的那几项。 */
export async function GET(_request: Request, context: Context) {
  const { id } = await context.params
  return respond(await callManager<Profile>(path(id)), '角色加载失败', (profile) => ({
    id: profile?.id ?? id,
    name: profile?.name ?? '',
    userAddress: profile?.userAddress ?? '',
    personality: profile?.personality ?? '',
    systemPrompt: profile?.systemPrompt ?? '',
    fromPreset: Boolean(profile?.templateId),
    ttsModelId: profile?.ttsModelId ?? null,
    ttsVoiceId: profile?.ttsVoiceId ?? '',
  }))
}

function text(value: unknown, max: number) {
  return typeof value === 'string' && value.length <= max ? value : undefined
}

/** 保存角色的设置，并让它立刻在对话里生效。没有传的项不动。 */
export async function PUT(request: Request, context: Context) {
  const { id } = await context.params
  const body = await request.json().catch(() => null) as Record<string, unknown> | null
  if (!body) return NextResponse.json({ error: '请求内容不正确' }, { status: 400 })
  const name = typeof body.name === 'string' ? body.name.trim() : undefined
  if (name !== undefined && (name.length < 1 || name.length > 64)) {
    return NextResponse.json({ error: '名字需要 1 到 64 个字' }, { status: 400 })
  }
  // 关系固定是朋友，这里不接受改成别的。
  const dto: Record<string, unknown> = {
    ...(name !== undefined ? { agentName: name } : {}),
    ...(text(body.userAddress, 64) !== undefined ? { userAddress: (body.userAddress as string).trim() } : {}),
    ...(text(body.personality, 1000) !== undefined ? { personality: body.personality } : {}),
    ...(text(body.systemPrompt, 16000) !== undefined ? { systemPrompt: body.systemPrompt } : {}),
    ...(text(body.ttsVoiceId, 32) !== undefined ? { ttsVoiceId: body.ttsVoiceId } : {}),
  }
  for (const [key, max] of [['userAddress', 64], ['personality', 1000], ['systemPrompt', 16000]] as const) {
    if (typeof body[key] === 'string' && (body[key] as string).length > max) {
      return NextResponse.json({ error: '内容太长了，请删掉一些再保存' }, { status: 400 })
    }
  }
  const result = await callManager(`${path(id)}?activate=true`, { method: 'PUT', body: JSON.stringify(dto) })
  return respond(result, '保存失败，请重试', () => ({ id }))
}

/** 删除角色，连同和它的对话记录。先删记录：记录删不掉就不删角色，免得留下找不到主人的对话。 */
export async function DELETE(_request: Request, context: Context) {
  const { id } = await context.params
  const cleared = await callManager(`/api/v1/conversations/threads/${encodeURIComponent(id)}`, { method: 'DELETE' })
  if (!cleared.ok) return respond(cleared, '删除失败，请重试')
  return respond(await callManager(path(id), { method: 'DELETE' }), '删除失败，请重试', () => ({ id }))
}
