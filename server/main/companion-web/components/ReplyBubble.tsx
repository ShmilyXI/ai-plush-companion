'use client'

import { Copy, Volume2 } from 'lucide-react'
import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react'

interface ReplyBubbleProps {
  text: string
  children?: ReactNode
  /** 重新朗读这条回复。没有语音的回复不传，菜单里就没有这一项。 */
  onReplay?: () => void
}

/** 按住多久算长按。和系统的长按差不多，太短容易在滚动时误触。 */
const LONG_PRESS_MS = 450
/** 按下后手指移动超过这么多就是在滚动，不算点也不算长按。 */
const MOVE_TOLERANCE = 8

/** 复制文字。剪贴板接口用不了时（非 HTTPS、旧浏览器）退回到选中再复制。 */
export async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    let copied = false
    try { copied = document.execCommand('copy') } catch { copied = false }
    area.remove()
    return copied
  }
}

/**
 * 角色的一条回复，显示成气泡。
 *
 * 点一下复制整条回复。长按（电脑上右键）弹出菜单：重新朗读、复制。
 * 气泡里的字不能选中：手机上长按会先弹出系统的选字菜单，和这里的菜单冲突。要整段文字时点一下复制就行。
 */
export default function ReplyBubble({ text, children, onReplay }: ReplyBubbleProps) {
  const [menu, setMenu] = useState(false)
  // 气泡在屏幕下半截时，菜单往上弹，免得被输入框挡住。
  const [up, setUp] = useState(false)
  const [copied, setCopied] = useState<'ok' | 'failed' | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const pressRef = useRef<{ x: number; y: number; timer: number; long: boolean } | null>(null)
  const suppressClickRef = useRef(false)
  const hintId = useId()

  const copy = useCallback(async () => {
    setMenu(false)
    setCopied(await copyText(text) ? 'ok' : 'failed')
  }, [text])

  useEffect(() => {
    if (!copied) return
    const timer = window.setTimeout(() => setCopied(null), 1600)
    return () => window.clearTimeout(timer)
  }, [copied])

  // 菜单打开时：点别处或按 Esc 关掉，焦点放到第一项上。
  useEffect(() => {
    if (!menu) return
    const box = rootRef.current?.getBoundingClientRect()
    setUp(Boolean(box && box.bottom > window.innerHeight * 0.6))
    rootRef.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus()
    const close = (event: Event) => {
      if (event instanceof KeyboardEvent ? event.key === 'Escape' : !rootRef.current?.contains(event.target as Node)) setMenu(false)
    }
    document.addEventListener('pointerdown', close)
    document.addEventListener('keydown', close)
    return () => { document.removeEventListener('pointerdown', close); document.removeEventListener('keydown', close) }
  }, [menu])

  const cancelPress = () => {
    if (pressRef.current) window.clearTimeout(pressRef.current.timer)
    pressRef.current = null
  }

  useEffect(() => cancelPress, [])

  return <div className="reply" ref={rootRef}>
    <div
      className={`message-body reply-bubble${menu ? ' pressed' : ''}`}
      role="button" tabIndex={0} aria-haspopup="menu" aria-expanded={menu} aria-describedby={hintId}
      title="点按复制，长按重新朗读"
      onPointerDown={(event) => {
        // 只管主键（手指、笔、鼠标左键）。右键走下面的 contextmenu。
        if (event.button > 0) return
        cancelPress()
        suppressClickRef.current = false
        const timer = window.setTimeout(() => {
          if (!pressRef.current) return
          pressRef.current.long = true
          suppressClickRef.current = true
          setMenu(true)
          navigator.vibrate?.(10)
        }, LONG_PRESS_MS)
        pressRef.current = { x: event.clientX, y: event.clientY, timer, long: false }
      }}
      onPointerMove={(event) => {
        const press = pressRef.current
        if (press && !press.long && Math.hypot(event.clientX - press.x, event.clientY - press.y) > MOVE_TOLERANCE) {
          cancelPress()
          suppressClickRef.current = true
        }
      }}
      onPointerUp={() => { if (pressRef.current && !pressRef.current.long) cancelPress() }}
      onPointerCancel={() => { cancelPress(); suppressClickRef.current = true }}
      onClick={() => {
        if (suppressClickRef.current) { suppressClickRef.current = false; return }
        void copy()
      }}
      onContextMenu={(event) => {
        // 电脑上右键、键盘上的菜单键，和手机长按一样打开菜单。
        event.preventDefault()
        cancelPress()
        setMenu(true)
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); void copy() }
      }}
    >
      {text}
      {children}
    </div>
    <span id={hintId} className="sr-only">点按复制，长按或按菜单键打开更多操作</span>
    {menu && <div className={`reply-menu${up ? ' up' : ''}`} role="menu" aria-label="这条回复">
      {onReplay && <button type="button" role="menuitem" onClick={() => { setMenu(false); onReplay() }}>
        <Volume2 size={17} aria-hidden="true" />重新朗读
      </button>}
      <button type="button" role="menuitem" onClick={() => void copy()}>
        <Copy size={17} aria-hidden="true" />复制
      </button>
    </div>}
    {copied && <span className="reply-copied" role="status">{copied === 'ok' ? '已复制' : '复制失败'}</span>}
  </div>
}
