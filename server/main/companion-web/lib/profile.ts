/** 个人中心和侧栏底部用的账号信息：谁登录着、什么套餐、最多几个角色、来了多少天。 */
export interface ProfileSummary {
  /** 显示用的名字：手机号中间四位打码，没有手机号时用账号名。 */
  label: string
  plan: string
  maxRoles: number | null
  /** 注册的时间（毫秒），算“已陪伴几天”。 */
  since: number | null
}

/** 手机号中间四位打码；带 +86 的先去掉。不是手机号的原样返回。 */
export function maskPhone(value: string) {
  const local = value.replace(/^\+?86(?=1\d{10}$)/, '')
  return /^1\d{10}$/.test(local) ? `${local.slice(0, 3)}****${local.slice(7)}` : value
}

/** manager-api 把长整数当字符串发过来（比如时间戳），这里两种都认。 */
export function toMillis(value: unknown) {
  const number = typeof value === 'number' ? value : typeof value === 'string' && /^\d{1,15}$/.test(value) ? Number(value) : NaN
  return Number.isFinite(number) ? number : null
}

async function data<T>(path: string): Promise<T | null> {
  try {
    const response = await fetch(path, { cache: 'no-store' })
    if (!response.ok) return null
    const payload = await response.json().catch(() => ({})) as { data?: T }
    return payload.data ?? null
  } catch { return null }
}

/** 读不到的部分留空，界面上就不显示那一项。 */
export async function loadProfile(): Promise<ProfileSummary> {
  const [session, plan] = await Promise.all([
    data<{ phone?: unknown; username?: unknown; mobile?: unknown; createDate?: unknown }>('/api/session'),
    data<{ name?: unknown; maxRoles?: unknown }>('/api/plan'),
  ])
  const phone = typeof session?.phone === 'string' ? session.phone : typeof session?.mobile === 'string' ? session.mobile : ''
  const username = typeof session?.username === 'string' ? session.username : ''
  // 注册时间是 “2026-11-24 10:10:08” 这样的服务端本地时间。Safari 不认中间的空格，换成 T 再解析。
  const created = typeof session?.createDate === 'string' ? new Date(session.createDate.replace(' ', 'T')).getTime()
    : typeof session?.createDate === 'number' ? session.createDate : NaN
  return {
    label: maskPhone(phone || username) || '我的账号',
    plan: typeof plan?.name === 'string' ? plan.name : '',
    maxRoles: typeof plan?.maxRoles === 'number' ? plan.maxRoles : null,
    since: Number.isFinite(created) ? created : null,
  }
}

/** 从注册那天算起是第几天，注册当天是第 1 天。 */
export function daysTogether(since: number | null, now = Date.now()) {
  if (since == null) return null
  const start = new Date(since); start.setHours(0, 0, 0, 0)
  const today = new Date(now); today.setHours(0, 0, 0, 0)
  return Math.max(1, Math.round((today.getTime() - start.getTime()) / 86_400_000) + 1)
}

/** 侧栏和个人中心里套餐那一行：“内测版 · 角色 4/5”。 */
export function planLine(profile: ProfileSummary | null, roles: number) {
  if (!profile) return ''
  const parts = [profile.plan, profile.maxRoles != null ? `角色 ${roles}/${profile.maxRoles}` : `${roles} 个角色`]
  return parts.filter(Boolean).join(' · ')
}
