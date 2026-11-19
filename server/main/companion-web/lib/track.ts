/**
 * 上报关键行为，用来在内测期间发现体验问题（例如麦克风打不开、重连失败）。
 *
 * 只报事件名和少量简单属性，绝不放对话内容。上报失败不影响任何功能，也不重试。
 */
type Props = Record<string, string | number | boolean>

const FLUSH_DELAY_MS = 2000
const MAX_BATCH = 20
const queue: Array<{ name: string; props?: Props }> = []
let timer: ReturnType<typeof setTimeout> | null = null
let listening = false

function flush() {
  if (timer !== null) { clearTimeout(timer); timer = null }
  const events = queue.splice(0, MAX_BATCH)
  if (!events.length || typeof window === 'undefined') return
  const body = JSON.stringify({ events })
  try {
    // 页面关闭时普通请求会被取消，sendBeacon 能保证发出去。
    if (typeof navigator.sendBeacon === 'function'
      && navigator.sendBeacon('/api/events', new Blob([body], { type: 'application/json' }))) return
    void fetch('/api/events', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body, keepalive: true,
    }).catch(() => undefined)
  } catch { /* 统计不能影响使用 */ }
}

export function track(name: string, props?: Props) {
  if (typeof window === 'undefined') return
  queue.push(props ? { name, props } : { name })
  if (!listening) {
    listening = true
    window.addEventListener('pagehide', flush)
  }
  if (queue.length >= MAX_BATCH) { flush(); return }
  if (timer === null) timer = setTimeout(flush, FLUSH_DELAY_MS)
}

/** 测试用：立刻发出排队中的事件。 */
export function flushTracking() { flush() }
