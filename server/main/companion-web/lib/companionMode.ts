/**
 * 陪伴模式：通话时用户一阵子没说话，角色会自己找个话题。
 *
 * 开关在设置页里，记在这个浏览器里，默认打开。只在语音通话时起作用，打字聊天时不会主动开口。
 * 角色会不会真的开口由服务端判断：没有自然的切入点时它会选择不说。
 */
export const PROACTIVE_KEY = 'companion.proactive'

/** 安静多久之后看一次要不要开口：第一次 30 秒，之后 1 分钟、2 分钟。连着三次用户都没接话就不再找了，等用户开口再重新算。 */
export const NUDGE_DELAYS_MS = [30_000, 60_000, 120_000]

export function readProactive(): boolean {
  try { return window.localStorage.getItem(PROACTIVE_KEY) !== '0' } catch { return true }
}

export function saveProactive(on: boolean) {
  try {
    if (on) window.localStorage.removeItem(PROACTIVE_KEY)
    else window.localStorage.setItem(PROACTIVE_KEY, '0')
  } catch { /* 存不了就只在这次生效 */ }
}
