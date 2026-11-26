'use client'

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { CharacterMood } from './Character'
import RoleCharacter, { characterKind } from './RoleCharacter'
import type { ReplyEmotion } from '../lib/mowa'

/** 缩小成桌宠时的大小（像素）。 */
const MINI = 88
const EDGE = 8
/** 用户把桌宠拖到了哪里。记在浏览器里。 */
const SPOT_KEY = 'companion.pet'
/** 桌宠默认待在对话区的右下角，输入栏上面。位置是它的中心占对话区宽高的比例，下边缘会被挡在输入栏上方。 */
const DEFAULT_SPOT = { fx: 0.9, fy: 1 }
/** 桌宠和输入栏（或开场建议）之间留的空。 */
const GAP = 10

interface PetProps {
  name: string
  mood: CharacterMood
  /** 这一轮回复的情绪，交给角色换表情。 */
  emotion?: ReplyEmotion | null
  level: () => number
  /** 展开：角色大大地站在对话上方。收起：缩成一只小桌宠，浮在对话区里。 */
  open: boolean
  /** 正在通话。展开时角色周围的光跟着声音一起一伏。 */
  live?: boolean
  /** 点了桌宠，要把它放大。 */
  onOpen: () => void
  /** 点了放大的角色，要把它缩回去。 */
  onClose?: () => void
}

function clamp(value: number, low: number, high: number) {
  return Math.min(high, Math.max(low, value))
}

/**
 * 角色一直在页面上。它的两种样子是同一个形象：展开时站在对话上方，收起时原地缩小、滑到右下角变成桌宠。
 * 桌宠可以在对话区里随意拖动，点一下放大，放大后再点一下缩回去。它照样会眨眼、听、想、开口。
 * Mowa Bot 角色放大后点它是戳一下（转圈、蹦、撒彩纸），缩回去用右上角的「缩小」；它的眼睛一直跟着指针转。
 */
export default function Pet({ name, mood, emotion = null, level, open, live = false, onOpen, onClose }: PetProps) {
  const boxRef = useRef<HTMLDivElement>(null)
  const [box, setBox] = useState<{ left: number; top: number; width: number; height: number; areaWidth: number; areaHeight: number; reserve: number } | null>(null)
  const levelRef = useRef(level)
  levelRef.current = level
  const [spot, setSpot] = useState(DEFAULT_SPOT)
  const [dragging, setDragging] = useState(false)
  // 刚打开页面时不做缩放动画，免得每次进来都看它缩一遍。
  const [armed, setArmed] = useState(false)
  const drag = useRef<{ x: number; y: number; fx: number; fy: number; moved: boolean } | null>(null)
  // Mowa Bot 角色放大后，点它是戳一下，不是缩回去。
  const pokes = characterKind(name) === 'mowa'
  const pokeRef = useRef<(() => void) | null>(null)
  const [hovered, setHovered] = useState(false)
  const justDragged = useRef(false)

  useEffect(() => {
    try {
      const saved = JSON.parse(window.localStorage.getItem(SPOT_KEY) || 'null') as { fx?: unknown; fy?: unknown } | null
      if (saved && typeof saved.fx === 'number' && typeof saved.fy === 'number') setSpot({ fx: clamp(saved.fx, 0, 1), fy: clamp(saved.fy, 0, 1) })
    } catch { /* 读不到就用默认位置 */ }
    const timer = window.setTimeout(() => setArmed(true), 400)
    return () => window.clearTimeout(timer)
  }, [])

  // 量一下自己和对话区的大小，以及底下输入栏（有开场建议时连同建议）占了多高，桌宠不能落到那里面去。
  const measure = useCallback(() => {
    const element = boxRef.current
    const area = element?.offsetParent as HTMLElement | null
    if (!element || !area) return
    const bottom = area.getBoundingClientRect().bottom
    const floor = area.querySelector('.starters') ?? area.querySelector('.dock')
    const reserve = floor ? Math.max(0, Math.round(bottom - floor.getBoundingClientRect().top + GAP)) : 0
    const next = {
      left: element.offsetLeft, top: element.offsetTop, width: element.offsetWidth, height: element.offsetHeight,
      areaWidth: area.clientWidth, areaHeight: area.clientHeight, reserve,
    }
    setBox((current) => current && Object.keys(next).every((key) => current[key as keyof typeof next] === next[key as keyof typeof next]) ? current : next)
  }, [])

  useEffect(() => {
    const element = boxRef.current
    const area = element?.offsetParent as HTMLElement | null
    if (!element || !area) return
    measure()
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(measure) : null
    observer?.observe(element); observer?.observe(area)
    return () => observer?.disconnect()
  }, [measure])
  // 开场建议出现或消失时输入栏上方的高度会变，每次重画都再量一次（值没变就什么都不做）。
  useLayoutEffect(measure)

  // 通话时放大的角色周围有一圈光，跟着声音（自己的或用户的）一起一伏。只在需要时每帧更新。
  useEffect(() => {
    const element = boxRef.current
    if (!element) return
    if (!(open && live)) { element.style.removeProperty('--voice'); return }
    let frame = 0
    let smooth = 0
    const tick = () => {
      frame = requestAnimationFrame(tick)
      const raw = Math.max(0, Math.min(1, levelRef.current() || 0))
      smooth += (raw - smooth) * (raw > smooth ? 0.4 : 0.12)
      element.style.setProperty('--voice', smooth.toFixed(3))
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [open, live])

  // 角色在展开的区域里实际占多大：区域很宽时按高度，很窄时按宽度。桌宠按这个大小缩到 MINI。
  const frame = box ? Math.min(box.height, box.width / 0.8) : 0
  let transform: string | undefined
  if (!open && box && frame > 0) {
    const centerX = clamp(spot.fx * box.areaWidth, MINI / 2 + EDGE, box.areaWidth - MINI / 2 - EDGE)
    const centerY = clamp(spot.fy * box.areaHeight, MINI / 2 + EDGE, Math.max(MINI / 2 + EDGE, box.areaHeight - box.reserve - MINI / 2))
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
    ref={boxRef} className={`pet ${open ? 'open' : 'mini'} mood-${mood} ${live ? 'live' : ''} ${dragging ? 'dragging' : ''} ${armed ? 'armed' : ''}`}
    style={transform ? { transform } : undefined}
  >
    <span className="pet-halo" aria-hidden="true" />
    <RoleCharacter name={name} mood={mood} emotion={emotion} level={level} lively={open || live} pokeRef={pokeRef} emphasis={open && hovered} />
    {open && pokes && <div
      className="pet-hit open poke" role="button" tabIndex={0} aria-label={`戳一下${name}`}
      style={{ width: frame, height: frame }}
      onPointerEnter={() => setHovered(true)} onPointerLeave={() => setHovered(false)}
      onClick={() => pokeRef.current?.()}
      onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); pokeRef.current?.() } }}
    />}
    {open && !pokes && onClose && <div
      className="pet-hit open" role="button" tabIndex={0} aria-label={`缩小${name}`}
      style={{ width: frame, height: frame }}
      onClick={onClose}
      onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onClose() } }}
    />}
    {!open && <div
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
