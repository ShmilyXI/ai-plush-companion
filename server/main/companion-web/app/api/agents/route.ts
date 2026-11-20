import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

export async function GET() {
  return respond(await callManager('/api/v1/agents'), '角色加载失败')
}
