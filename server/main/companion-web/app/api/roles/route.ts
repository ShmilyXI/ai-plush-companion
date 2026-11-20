import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

/**
 * 空白角色的起点。只写所有角色都要守的几条约定，性格留给用户自己填。
 * 平台的内容安全守则由对话服务另外附加，不依赖这段文字，用户改掉这里也绕不开。
 */
const BLANK_PROMPT = [
  '你是用户的陪伴角色，和用户是朋友关系。',
  '用简短、自然的口语聊天，像朋友之间说话，不要写成文章。',
  '你是 AI，不冒充真人；尊重用户自己的选择、现实里的关系和个人边界。',
  '不要把叹气、笑声或括号里的动作描写写进回复。',
].join('\n')

export async function POST(request: Request) {
  const body = await request.json().catch(() => null) as { templateId?: unknown; name?: unknown; blank?: unknown } | null
  const name = typeof body?.name === 'string' ? body.name.trim() : ''
  if (!body || !name || name.length > 64) {
    return NextResponse.json({ error: '请给角色起个名字' }, { status: 400 })
  }
  let templateId = typeof body.templateId === 'string' ? body.templateId : ''
  if (body.blank === true) {
    // 后端的角色都从一个预设复制出来（模型和声音跟着预设走），空白角色借第一个预设的配置，再把人设清空。
    const templates = await callManager<Array<{ id?: string }>>('/companion/templates')
    templateId = templates.ok && Array.isArray(templates.data) ? String(templates.data[0]?.id || '') : ''
    if (!templateId) return NextResponse.json({ error: '暂时不能新建角色，请稍后再试' }, { status: 400 })
  }
  if (!templateId) return NextResponse.json({ error: '请选择一个角色' }, { status: 400 })

  const created = await callManager<string>('/companion/profiles', { method: 'POST', body: JSON.stringify({ templateId, name }) })
  if (!created.ok || body.blank !== true || !created.data) return respond(created, '角色创建失败', (id) => ({ id }))
  const id = created.data
  const blanked = await callManager(`/companion/profiles/${encodeURIComponent(id)}?activate=true`, {
    method: 'PUT', body: JSON.stringify({ personality: '', systemPrompt: BLANK_PROMPT }),
  })
  if (!blanked.ok) {
    // 人设没清掉的角色不能留着，否则用户会得到一个顶着新名字的预设角色。
    await callManager(`/companion/profiles/${encodeURIComponent(id)}`, { method: 'DELETE' }).catch(() => null)
    return respond(blanked, '角色创建失败')
  }
  return NextResponse.json({ data: { id } })
}
