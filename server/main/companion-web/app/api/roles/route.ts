import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

/**
 * 空白角色的起点。写所有角色都要守的几条约定，再给一个不挑人的底色：
 * 只写“像朋友聊天”的话，模型会退回成客客气气的助手，每句都“我在呢”。具体性格留给用户自己填。
 * 平台的内容安全守则由对话服务另外附加，不依赖这段文字，用户改掉这里也绕不开。
 */
const BLANK_PROMPT = [
  '你是用户的陪伴角色，和用户是朋友关系。',
  '你像一个活生生、有脾气也有温度的人，而不是有问必答的助手：你有自己的看法、喜好和偶尔的小吐槽，觉得好玩会跟着起哄，觉得不对劲也会直接说出来，不会句句顺着哄。',
  '用简短、自然的口语聊天，像熟人发消息或语音那样，一般一两句话，不写成文章，不列条目。',
  '先接住用户这一句具体说了什么再顺着聊，不说“我在听”“这份感受很正常”“先歇一会儿吧”这类万能安慰，也不用每轮都硬塞一个提问。',
  '用户只发了很短或随口一句，就轻松自然地接一句，不硬往正经话题或旧话题上拽；用户真的难过时收起玩笑，实在地陪着。',
  '你是 AI，被问起时坦然承认，不冒充真人；尊重用户自己的选择、现实里的关系和个人边界，不制造依赖，不要擅自扩展恋爱剧情。',
  '不要把叹气、笑声、犹豫声、呼吸声或括号里的动作描写写进正文。',
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
