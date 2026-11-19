import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

export async function GET() {
  return respond(await callManager('/companion/templates'), '角色模板加载失败')
}
