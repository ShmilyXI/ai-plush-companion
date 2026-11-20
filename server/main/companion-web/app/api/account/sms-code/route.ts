import { respond } from '@/lib/respond'
import { callManager } from '@/lib/server-manager'

/** 给当前账号的手机号发一个验证码，用于注销前确认是本人。 */
export async function POST() {
  return respond(await callManager('/app/v1/auth/account/sms-code', { method: 'POST' }), '验证码发送失败', () => ({ sent: true }))
}
