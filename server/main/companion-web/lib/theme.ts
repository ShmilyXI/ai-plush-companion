/**
 * 页面的主题：浅色、深色，或者跟随系统。
 *
 * 选了浅色或深色时，在 <html> 上写一个 data-theme 属性，样式按它来；跟随系统时不写这个属性，
 * 样式按系统的设置来。选择记在浏览器里。
 */
export type Theme = 'auto' | 'light' | 'dark'

export const THEME_KEY = 'companion.theme'

export const THEMES: Array<{ key: Theme; label: string }> = [
  { key: 'auto', label: '跟随系统' },
  { key: 'light', label: '浅色' },
  { key: 'dark', label: '深色' },
]

export function readTheme(): Theme {
  try {
    const saved = window.localStorage.getItem(THEME_KEY)
    return saved === 'light' || saved === 'dark' ? saved : 'auto'
  } catch { return 'auto' }
}

export function applyTheme(theme: Theme) {
  if (theme === 'auto') delete document.documentElement.dataset.theme
  else document.documentElement.dataset.theme = theme
  try {
    if (theme === 'auto') window.localStorage.removeItem(THEME_KEY)
    else window.localStorage.setItem(THEME_KEY, theme)
  } catch { /* 存不了就只在这次生效 */ }
}

/**
 * 放在页面最前面执行的一小段脚本：在第一次绘制之前就把记住的主题写上，
 * 否则选了深色的人每次打开都会先闪一下浅色。
 */
export const THEME_BOOT_SCRIPT = `try{var t=localStorage.getItem('${THEME_KEY}');if(t==='light'||t==='dark')document.documentElement.dataset.theme=t}catch(e){}`
