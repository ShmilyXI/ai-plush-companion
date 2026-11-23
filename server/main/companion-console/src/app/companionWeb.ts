import http, { type ApiResult } from '../api/http'

const LOOPBACK = new Set(['localhost', '127.0.0.1', '[::1]'])

/**
 * 网页端（companion-web）的地址。
 *
 * 嵌在管理台里时，网页端的登录 Cookie 只有和管理台“同站”才会被浏览器带上。
 * localhost 和 127.0.0.1 算两个站，本机开发时一个用 localhost、一个用 127.0.0.1 打开，嵌进来的网页端就一直是未登录。
 * 所以两边都是本机地址时，网页端跟着管理台当前用的主机名走，端口不变。
 */
export function companionWebUrl(configured = import.meta.env.VITE_COMPANION_WEB_URL || 'http://127.0.0.1:8010', here = window.location) {
  try {
    const url = new URL(configured)
    if (LOOPBACK.has(url.hostname) && LOOPBACK.has(here.hostname) && url.hostname !== here.hostname) {
      url.hostname = here.hostname
      return url.toString().replace(/\/$/, '')
    }
  } catch {
    // 地址无效时原样返回，由页面提示。
  }
  return configured
}

/**
 * 向后端要一个一次性的启动码。网页端拿它换成当前管理台账号的登录状态，用户不用再登录一次。
 * 启动码只能用一次、很快过期，并且只发给后端允许的网页端地址（COMPANION_WEB_ALLOWED_ORIGINS）。
 */
export async function requestWebBootstrap(appUrl = companionWebUrl()) {
  const response = await http.post<ApiResult<{ code: string }>>('/api/v1/web-sessions/bootstrap', {
    audience: 'companion-web', origin: new URL(appUrl).origin,
  })
  if (response.data.code !== 0 || !response.data.data?.code) throw new Error(response.data.msg || '登录桥接失败')
  return response.data.data
}

/**
 * 在新窗口里以当前管理台账号打开网页端。
 *
 * 窗口要在点击的那一刻就打开，否则浏览器会当成弹窗拦掉；拿到启动码后再把地址填进去。
 * 拿不到启动码时照样打开网页端，只是要在那边再登录一次。
 */
export async function openCompanionWeb(appUrl = companionWebUrl()) {
  const opened = window.open('about:blank', '_blank')
  const target = new URL(appUrl)
  try {
    const { code } = await requestWebBootstrap(appUrl)
    target.searchParams.set('code', code)
  } catch {
    // 启动码要不到：不带它打开，网页端会显示登录页。
  }
  if (opened) {
    // 新窗口不需要也不应该能操作管理台这一页。
    try { opened.opener = null } catch { /* 有的浏览器不让改，不影响打开 */ }
    opened.location.href = target.toString()
  } else {
    window.location.assign(target.toString())
  }
}
