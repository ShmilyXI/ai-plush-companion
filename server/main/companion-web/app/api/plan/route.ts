import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

/** 当前用户的套餐：名字和最多能有几个角色。个人中心用。 */
export async function GET() {
  const result = await callManager<Record<string, unknown>>('/companion/subscription')
  return respond(result, '套餐信息加载失败', (plan) => ({
    name: typeof plan?.planName === 'string' ? plan.planName : '',
    maxRoles: typeof plan?.maxProfiles === 'number' ? plan.maxProfiles : null,
  }))
}
