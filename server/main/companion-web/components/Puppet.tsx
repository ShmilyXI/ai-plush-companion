'use client'

import { useEffect, useRef, useState, type MutableRefObject } from 'react'
import { roleLook } from '../lib/roles'
import type { CharacterMood } from './Character'

interface PuppetProps {
  name: string
  mood: CharacterMood
  /** 每一帧调用一次，返回 0 到 1 的音量。 */
  level?: () => number
  /** false 表示角色缩在角落里：听和想的时候不逐帧歪头，只用 CSS 摆个姿势，省电。开口说话时照常对口型。 */
  lively?: boolean
  /** 外面点了角色时调用 pokeRef.current() 戳它一下：蹦一下、扭一扭、吓一跳，每次换一种。 */
  pokeRef?: MutableRefObject<(() => void) | null>
}

/** 戳一下的三种反应，轮着来。动画做在包着身体的一层上，不碰身体自己的呼吸、歪头和对口型。 */
const POKES: Array<{ frames: Keyframe[]; ms: number; surprise?: boolean }> = [
  // 蹦一下：起跳、落地压扁一点、回弹。
  { ms: 620, frames: [
    { translate: '0 0', scale: '1 1' }, { translate: '0 -9%', scale: '0.97 1.04', offset: 0.35 },
    { translate: '0 0', scale: '1.06 0.93', offset: 0.7 }, { translate: '0 0', scale: '1 1' },
  ] },
  // 扭一扭。
  { ms: 700, frames: [
    { rotate: '0deg' }, { rotate: '-7deg', offset: 0.2 }, { rotate: '6deg', offset: 0.45 }, { rotate: '-3deg', offset: 0.7 }, { rotate: '0deg' },
  ] },
  // 吓一跳：猛地放大一下，同时张嘴、眨眼。
  { ms: 560, surprise: true, frames: [
    { scale: '1' }, { scale: '1.07', offset: 0.18 }, { scale: '0.98', offset: 0.55 }, { scale: '1' },
  ] },
]
/** 两次戳之间至少隔多久，连点时太快的那一下不算。 */
const POKE_COOLDOWN = 600

/**
 * 用图片做的会动的角色：一张正面的全身像，上面叠着“闭眼”和“张嘴”两块小补丁。
 *
 * 眨眼和说话就是让补丁显示或隐藏；呼吸、歪头、晃动是对整张图做旋转和很轻的缩放。
 * 说话时只有嘴跟着音量动，身体不跟着声音上下颠：一个字一颠看起来像在蹦，很魔性。
 * 嘴的开合跟着角色自己声音的音量走。补丁只盖住眼睛和嘴，其余部分永远是同一张图，所以切换时不会抖。
 *
 * 角色平时一直待在页面角落，所以闲着（idle、offline）时不跑逐帧的动画：呼吸交给 CSS，眨眼用定时器。
 * 只有在听、在想、在说的时候才逐帧计算歪头和对口型。
 */
export default function Puppet({ name, mood, level, lively = true, pokeRef }: PuppetProps) {
  const look = roleLook(name)
  const bodyRef = useRef<HTMLDivElement>(null)
  const reactRef = useRef<HTMLDivElement>(null)
  const blinkRef = useRef<HTMLImageElement>(null)
  const talkRef = useRef<HTMLImageElement>(null)
  const moodRef = useRef(mood)
  moodRef.current = mood
  const levelRef = useRef(level)
  levelRef.current = level
  const [failed, setFailed] = useState(false)
  const art = failed ? undefined : look.art
  useEffect(() => { setFailed(false) }, [look.art?.base])
  const active = mood === 'speaking' || (lively && (mood === 'hearing' || mood === 'thinking'))

  // 闲着：隔几秒眨一下眼，断线时一直闭着；不碰整张图的位置，呼吸由 CSS 做。
  useEffect(() => {
    if (!art || active) return
    if (bodyRef.current) bodyRef.current.style.transform = ''
    if (talkRef.current) talkRef.current.style.opacity = '0'
    const blink = blinkRef.current
    if (!blink) return
    if (mood === 'offline') { blink.style.opacity = '1'; return }
    let timer = 0
    const schedule = () => {
      timer = window.setTimeout(() => {
        blink.style.opacity = '1'
        timer = window.setTimeout(() => { blink.style.opacity = '0'; schedule() }, 140)
      }, 2200 + Math.random() * 3200)
    }
    blink.style.opacity = '0'
    schedule()
    return () => window.clearTimeout(timer)
  }, [art, active, mood])

  useEffect(() => {
    if (!art || !active) return
    const calm = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
    let frame = 0
    let last = performance.now()
    let smooth = 0
    let tilt = 0
    let lean = 0
    let nextBlink = last + 1600 + Math.random() * 2400
    let blinkUntil = 0
    let mouthOpen = false
    let mouthSince = 0
    const draw = (now: number) => {
      frame = requestAnimationFrame(draw)
      const delta = Math.min(0.05, (now - last) / 1000)
      last = now
      const current = moodRef.current
      const raw = Math.max(0, Math.min(1, levelRef.current?.() ?? 0))
      smooth += (raw - smooth) * (raw > smooth ? 0.5 : 0.18)
      const voice = current === 'speaking' ? smooth : 0

      // 眨眼。连接断开时一直闭着。
      if (now > nextBlink) { blinkUntil = now + 140; nextBlink = now + 2200 + Math.random() * 3200 }
      if (blinkRef.current) blinkRef.current.style.opacity = now < blinkUntil || current === 'offline' ? '1' : '0'

      // 嘴：声音够响就张开，轻下去就合上。每个状态至少停留一小会儿，免得抖成一片。
      const wantOpen = voice > (mouthOpen ? 0.12 : 0.22)
      if (wantOpen !== mouthOpen && now - mouthSince > 70) { mouthOpen = wantOpen; mouthSince = now }
      // 一直很响的时候也让嘴隔一会儿合一下，看起来是在一个字一个字地说。
      const shut = mouthOpen && now - mouthSince > 150 && Math.sin(now / 55) > 0.6
      if (talkRef.current) talkRef.current.style.opacity = mouthOpen && !shut ? '1' : '0'

      const body = bodyRef.current
      if (!body) return
      if (calm) { body.style.transform = ''; return }
      const t = now / 1000
      const wantedTilt = current === 'hearing' ? 5 : current === 'thinking' ? -4 + Math.sin(t * 0.9) * 2.5 : current === 'speaking' ? Math.sin(t * 2.1) * 1.6 : Math.sin(t * 0.5) * 1.2
      const wantedLean = current === 'hearing' ? 1 : 0
      const ease = 1 - Math.exp(-delta * 6)
      tilt += (wantedTilt - tilt) * ease
      lean += (wantedLean - lean) * ease
      const breath = Math.sin(t * 1.9) * 0.012
      const lift = lean * 6
      body.style.transform = `translateY(${(-lift).toFixed(2)}px) rotate(${tilt.toFixed(2)}deg) scale(${(1 + lean * 0.03).toFixed(4)}, ${(1 + lean * 0.03 + breath).toFixed(4)})`
    }
    frame = requestAnimationFrame(draw)
    return () => cancelAnimationFrame(frame)
  }, [art, active])

  // 戳一下。
  useEffect(() => {
    if (!pokeRef) return
    let index = 0
    let last = -Infinity
    const timers: number[] = []
    pokeRef.current = () => {
      const now = performance.now()
      if (now - last < POKE_COOLDOWN) return
      last = now
      const poke = POKES[index % POKES.length]
      index += 1
      const calm = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
      const wrapper = reactRef.current
      if (!calm && wrapper && typeof wrapper.animate === 'function') wrapper.animate(poke.frames, { duration: poke.ms, easing: 'ease-out' })
      if (poke.surprise) {
        const talk = talkRef.current; const blink = blinkRef.current
        if (talk) { talk.style.opacity = '1'; timers.push(window.setTimeout(() => { talk.style.opacity = '0' }, 420)) }
        if (blink) {
          timers.push(window.setTimeout(() => { blink.style.opacity = '1' }, 260))
          timers.push(window.setTimeout(() => { blink.style.opacity = '0' }, 380))
        }
      }
    }
    return () => { pokeRef.current = null; timers.forEach((timer) => window.clearTimeout(timer)) }
  }, [pokeRef])

  return <div className={`puppet mood-${mood} ${active ? '' : 'resting'}`} aria-hidden="true">
    <span className="puppet-shadow" />
    {art
      ? <div ref={reactRef} className="puppet-react"><div ref={bodyRef} className="puppet-body">
        {/* eslint-disable @next/next/no-img-element */}
        <img src={art.base} alt="" draggable={false} onError={() => setFailed(true)} />
        {art.blink && <img ref={blinkRef} className="puppet-layer" src={art.blink} alt="" draggable={false} style={{ opacity: 0 }} />}
        {art.talk && <img ref={talkRef} className="puppet-layer" src={art.talk} alt="" draggable={false} style={{ opacity: 0 }} />}
        {/* eslint-enable @next/next/no-img-element */}
      </div></div>
      : <span className="character-initial">{name.trim().slice(0, 1) || '·'}</span>}
  </div>
}
