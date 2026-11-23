'use client'

import { Copy, Volume2 } from 'lucide-react'
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

interface ReplyBubbleProps {
  text: string
  children?: ReactNode
  /** 朗读这条回复。读不了时（没有语音，也找不到是哪一轮）不传，菜单里就只有复制。 */
  onRead?: () => void
}

/** 按住多久算长按。和系统的长按差不多，太短容易在滚动时误触。 */
const LONG_PRESS_MS = 450
/** 按下后手指移动超过这么多就是在滚动，不算长按。 */
const MOVE_TOLERANCE = 8
/** 菜单离屏幕边缘和气泡的距离。 */
const GAP = 10

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
 * 长按（电脑上右键、键盘上回车或菜单键）弹出菜单：复制、朗读。点一下什么都不做，免得滚动时误触。
 * 菜单和一层遮罩放在页面最上层（document.body），不会被后面的消息盖住；被选中的气泡在遮罩上面再画一份，看得出是哪一条。
 * 气泡里的字不能选中：手机上长按会先弹出系统的选字菜单，和这里的菜单冲突。
 */
export default function ReplyBubble({ text, children, onRead }: ReplyBubbleProps) {
  const [box, setBox] = useState<DOMRect | null>(null)
  const [place, setPlace] = useState<{ top: number; left: number } | null>(null)
  const [copied, setCopied] = useState<'ok' | 'failed' | null>(null)
  const bubbleRef = useRef<HTMLDivElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  const pressRef = useRef<{ x: number; y: number; timer: number; long: boolean } | null>(null)
  const hintId = useId()
  const open = box !== null

  const show = useCallback(() => {
    const rect = bubbleRef.current?.getBoundingClientRect()
    if (rect) setBox(rect)
  }, [])
  const close = useCallback((refocus = false) => {
    setBox(null); setPlace(null)
    if (refocus) bubbleRef.current?.focus({ preventScroll: true })
  }, [])

  // 菜单放在气泡下面，下面放不下就放上面；左右不出屏幕。量好菜单的大小再放，放好之前不显示。
  useLayoutEffect(() => {
    if (!box || !menuRef.current) return
    const menu = menuRef.current.getBoundingClientRect()
    const below = box.bottom + GAP
    const top = below + menu.height <= window.innerHeight - GAP ? below : Math.max(GAP, box.top - GAP - menu.height)
    const left = Math.min(Math.max(GAP, box.left), window.innerWidth - GAP - menu.width)
    setPlace({ top, left })
  }, [box])

  // 菜单打开时：焦点放到第一项；按 Esc 或改变窗口大小都关掉。
  useEffect(() => {
    if (!open) return
    menuRef.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus({ preventScroll: true })
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') close(true) }
    const onResize = () => close()
    document.addEventListener('keydown', onKey)
    window.addEventListener('resize', onResize)
    return () => { document.removeEventListener('keydown', onKey); window.removeEventListener('resize', onResize) }
  }, [open, close])

  useEffect(() => {
    if (!copied) return
    const timer = window.setTimeout(() => setCopied(null), 1600)
    return () => window.clearTimeout(timer)
  }, [copied])

  const cancelPress = () => {
    if (pressRef.current) window.clearTimeout(pressRef.current.timer)
    pressRef.current = null
  }
  useEffect(() => cancelPress, [])

  const copy = async () => {
    close(true)
    setCopied(await copyText(text) ? 'ok' : 'failed')
  }

  return <div className="reply">
    <div
      ref={bubbleRef}
      className={`message-body reply-bubble${open ? ' pressed' : ''}`}
      role="button" tabIndex={0} aria-haspopup="menu" aria-expanded={open} aria-describedby={hintId}
      onPointerDown={(event) => {
        // 只管主键（手指、笔、鼠标左键）。右键走下面的 contextmenu。
        if (event.button > 0) return
        cancelPress()
        const timer = window.setTimeout(() => {
          if (!pressRef.current) return
          pressRef.current.long = true
          navigator.vibrate?.(10)
          show()
        }, LONG_PRESS_MS)
        pressRef.current = { x: event.clientX, y: event.clientY, timer, long: false }
      }}
      onPointerMove={(event) => {
        const press = pressRef.current
        if (press && !press.long && Math.hypot(event.clientX - press.x, event.clientY - press.y) > MOVE_TOLERANCE) cancelPress()
      }}
      onPointerUp={() => { if (pressRef.current && !pressRef.current.long) cancelPress() }}
      onPointerCancel={cancelPress}
      onContextMenu={(event) => {
        // 电脑上右键、键盘上的菜单键，以及部分手机的长按，都会走到这里。
        event.preventDefault()
        cancelPress()
        show()
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); show() }
      }}
    >
      {text}
      {children}
    </div>
    <span id={hintId} className="sr-only">长按、右键或按回车打开菜单：复制、朗读</span>
    {copied && <span className="reply-copied" role="status">{copied === 'ok' ? '已复制' : '复制失败'}</span>}
    {box && createPortal(<>
      <div className="reply-overlay" onPointerDown={(event) => { event.preventDefault(); close(true) }} onWheel={() => close()} />
      <div className="reply-lifted" aria-hidden="true" style={{ top: box.top, left: box.left, width: box.width, minHeight: box.height }}>{text}</div>
      <div
        ref={menuRef} className="reply-menu" role="menu" aria-label="这条回复"
        style={place ? { top: place.top, left: place.left } : { top: 0, left: 0, visibility: 'hidden' }}
      >
        <button type="button" role="menuitem" onClick={() => void copy()}>
          <Copy size={22} aria-hidden="true" /><span>复制</span>
        </button>
        {onRead && <button type="button" role="menuitem" onClick={() => { close(true); onRead() }}>
          <Volume2 size={22} aria-hidden="true" /><span>朗读</span>
        </button>}
      </div>
    </>, document.body)}
  </div>
}
