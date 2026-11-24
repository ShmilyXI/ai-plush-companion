import { sm2 } from 'sm-crypto'

/** 注册时的密码规则，和 manager-api 一致：8 到 32 位，同时有大写字母、小写字母和数字。 */
export const PASSWORD_RULE = '8 到 32 位，同时包含大写字母、小写字母和数字'

export function passwordProblem(password: string) {
  if (password.length < 8 || password.length > 32) return `密码需要${PASSWORD_RULE}`
  if (!/[A-Z]/.test(password) || !/[a-z]/.test(password) || !/\d/.test(password)) return `密码需要${PASSWORD_RULE}`
  return ''
}

let cachedKey: Promise<string> | null = null

/** 服务端的 SM2 公钥。密码在浏览器里加密后才发出去，明文不离开这个页面。 */
async function publicKey() {
  cachedKey ??= fetch('/api/auth/pub-key', { cache: 'no-store' }).then(async (response) => {
    const payload = await response.json().catch(() => ({})) as { data?: { publicKey?: unknown } }
    const key = typeof payload.data?.publicKey === 'string' ? payload.data.publicKey.trim() : ''
    if (!/^(?:04)?[0-9a-f]{128}$/i.test(key)) throw new Error('暂时无法加密密码，请稍后重试')
    return key.length === 128 ? `04${key}` : key
  }).catch((reason) => { cachedKey = null; throw reason })
  return cachedKey
}

/** 和管理台一样的加密方式：SM2 C1C3C2，前面加 04。 */
export async function encryptPassword(password: string) {
  const cipher = sm2.doEncrypt(password, await publicKey(), 1)
  if (!/^[0-9a-f]+$/i.test(cipher)) throw new Error('密码加密失败，请重试')
  return `04${cipher}`
}
