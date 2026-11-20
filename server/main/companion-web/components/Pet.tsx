'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import Character, { type CharacterMood } from './Character'

/** 缩小成桌宠时的大小（像素）。 */
const MINI = 108
const EDGE = 8
/** 用户把桌宠拖到了哪里。记在浏览器里。 */
const SPOT_KEY = 'companion.pet'
/** 桌宠默认待在对话区的右侧偏下，不挡输入栏。位置是它的中心占对话区宽高的比例。 */
const DEFAULT_SPOT = { fx: 0.84, fy: 0.6 }

interface PetProps {
  name: string
  mood: CharacterMood
  level: () => number
  /** 展开：角色大大地站在对话上方。收起：缩成一只小桌宠，浮在对话区里。 */
  open: boolean
  /** 通话结束了，角色正在淡出。 */
  leaving?: boolean
  /** 点了桌宠，要把它放大。 */
  onOpen: () => void
}

function clamp(value: number, low: number, high: number) {
  return Math.min(high, Math.max(low, value))
}

/**
 * 角色只在通话时出现。它的两种样子用的是同一个 3D 形象：展开时站在对话上方，收起时原地缩小、滑到一边变成桌宠。
 * 桌宠可以在对话区里随意拖动，点一下放大回来。它照样会眨眼、听、想、开口。
 */
export default function Pet({ name, mood, level, open, leaving = false, onOpen }: PetProps) {
  const boxRef = useRef<HTMLDivElement>(null)
  const [box, setBox] = useState<{ left: number; top: number; width: number; height: number; areaWidth: number; areaHeight: number } | null>(null)
  const [spot, setSpot] = useState(DEFAULT_SPOT)
  const [dragging, setDragging] = useState(false)
  // 刚打开页面时不做缩放动画，免得每次进来都看它缩一遍。
  const [armed, setArmed] = useState(false)
  const drag = useRef<{ x: number; y: number; fx: number; fy: number; moved: boolean } | null>(null)
  const justDragged = useRef(false)

  useEffect(() => {
    try {
      const saved = JSON.parse(window.localStorage.getItem(SPOT_KEY) || 'null') as { fx?: unknown; fy?: unknown } | null
      if (saved && typeof saved.fx === 'number' && typeof saved.fy === 'number') setSpot({ fx: clamp(saved.fx, 0, 1), fy: clamp(saved.fy, 0, 1) })
    } catch { /* 读不到就用默认位置 */ }
    const timer = window.setTimeout(() => setArmed(true), 400)
    return () => window.clearTimeout(timer)
  }, [])

  useEffect(() => {
    const element = boxRef.current
    const area = element?.offsetParent as HTMLElement | null
    if (!element || !area) return
    const measure = () => setBox({
      left: element.offsetLeft, top: element.offsetTop, width: element.offsetWidth, height: element.offsetHeight,
      areaWidth: area.clientWidth, areaHeight: area.clientHeight,
    })
    measure()
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(measure) : null
    observer?.observe(element); observer?.observe(area)
    return () => observer?.disconnect()
  }, [])

  // 角色在展开的区域里实际占多大：区域很宽时按高度，很窄时按宽度。桌宠按这个大小缩到 MINI。
  const frame = box ? Math.min(box.height, box.width / 0.8) : 0
  let transform: string | undefined
  if (!open && box && frame > 0) {
    const centerX = clamp(spot.fx * box.areaWidth, MINI / 2 + EDGE, box.areaWidth - MINI / 2 - EDGE)
    const centerY = clamp(spot.fy * box.areaHeight, MINI / 2 + EDGE, box.areaHeight - MINI / 2 - EDGE)
    transform = `translate(${Math.round(centerX - (box.left + box.width / 2))}px, ${Math.round(centerY - (box.top + box.height / 2))}px) scale(${(MINI / frame).toFixed(4)})`
  }

  const onPointerDown = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    if (open) return
    event.currentTarget.setPointerCapture?.(event.pointerId)
    drag.current = { x: event.clientX, y: event.clientY, fx: spot.fx, fy: spot.fy, moved: false }
  }, [open, spot])

  const onPointerMove = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const start = drag.current
    if (!start) return
    const dx = event.clientX - start.x
    const dy = event.clientY - start.y
    // 手指轻微的抖动不算拖动，否则点一下就放大会很难点中。
    if (!start.moved && Math.hypot(dx, dy) < 6) return
    start.moved = true
    setDragging(true)
    if (box) setSpot({ fx: clamp(start.fx + dx / box.areaWidth, 0, 1), fy: clamp(start.fy + dy / box.areaHeight, 0, 1) })
  }, [box])

  const onPointerUp = useCallback(() => {
    const start = drag.current
    drag.current = null
    setDragging(false)
    if (!start?.moved) return
    justDragged.current = true
    setSpot((current) => {
      try { window.localStorage.setItem(SPOT_KEY, JSON.stringify(current)) } catch { /* 存不了就只在这次生效 */ }
      return current
    })
  }, [])

  return <div
    ref={boxRef} className={`pet ${open ? 'open' : 'mini'} ${dragging ? 'dragging' : ''} ${armed ? 'armed' : ''} ${leaving ? 'leaving' : ''}`}
    style={transform ? { transform } : undefined}
  >
    <Character name={name} mood={mood} level={level} />
    {!open && !leaving && <div
      className="pet-hit" role="button" tabIndex={0} aria-label={`放大${name}`}
      style={{ width: frame, height: frame }}
      onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}
      onClick={() => {
        // 拖完松手也会触发一次点击，那一次不算。
        if (justDragged.current) { justDragged.current = false; return }
        onOpen()
      }}
      onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onOpen() } }}
    />}
  </div>
}
