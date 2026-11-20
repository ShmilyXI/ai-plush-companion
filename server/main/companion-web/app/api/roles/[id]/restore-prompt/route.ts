import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

/** 把提示词恢复成预设的原样，并立刻生效。只有从预设创建的角色能恢复。 */
export async function POST(_request: Request, context: { params: Promise<{ id: string }> }) {
  const { id } = await context.params
  const path = `/companion/profiles/${encodeURIComponent(id)}`
  const restored = await callManager(`${path}/restore-prompt`, { method: 'POST' })
  if (!restored.ok) return respond(restored, '恢复失败，请重试')
  // 恢复只改了保存的内容，再空保存一次带上生效。
  return respond(await callManager(`${path}?activate=true`, { method: 'PUT', body: '{}' }), '恢复失败，请重试', () => ({ id }))
}
