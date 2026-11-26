'use client'

import { CharacterMark, useTapCycle, type CharacterHandle, type CharacterState, type HudSpec } from '@mowa-bot/react'
import '@mowa-bot/react/styles.css'
import { useEffect, useRef, useState, type MutableRefObject } from 'react'
import { BORED_AFTER_MS, DROWSY_AFTER_MS, EMOTION_LINGER_MS, WAKING_MS, emotionHops, mowaLook, mowaState, type IdleStage, type ReplyEmotion } from '../lib/mowa'
import { mowaDrawing } from '../lib/mowaRender'
import type { CharacterMood } from './Character'

interface MowaCharacterProps {
  name: string
  mood: CharacterMood
  /** 每一帧调用一次，返回 0 到 1 的音量：角色说话时是它自己声音的音量。 */
  level?: () => number
  /** false 表示角色缩在角落里：不做随机的小动作。 */
  lively?: boolean
  /** 这一轮回复的情绪，没有时为 null。 */
  emotion?: ReplyEmotion | null
  /** 角色设置里点了某个表情时，预览演这个状态，不管通话状态。 */
  demoState?: CharacterState | null
  /** 外面点了角色时调用 pokeRef.current()：转个圈、蹦一下或撒彩纸，每次换一种。 */
  pokeRef?: MutableRefObject<(() => void) | null>
  /** 指针停在角色上：眼睛放大、往中间凑一点。 */
  emphasis?: boolean
}

/** 声浪最多每秒刷新这么多次。音量是逐帧读的，但没必要每帧都让 React 重画。 */
const METER_FPS = 12

/**
 * 用 Mowa Bot 画的角色：一个由代码生成的 SVG 小角色，有几十种动画状态。
 *
 * 通话状态决定大的样子（睡、听、想），角色说话时换成这条回复的情绪，
 * 身边一圈声浪跟着它自己声音的音量起伏。开心和惊讶的回复到来时跳一下。
 * 说完以后情绪还留几秒，只发文字的回复也看得到表情。
 */
export default function MowaCharacter({ name, mood, level, lively = true, emotion = null, demoState = null, pokeRef, emphasis = false }: MowaCharacterProps) {
  const drawing = mowaDrawing(mowaLook(name))
  const markRef = useRef<CharacterHandle>(null)
  const [lingering, setLingering] = useState(false)
  const [stage, setStage] = useState<IdleStage>('rest')
  const wasOffline = useRef(mood === 'offline')
  const [meter, setMeter] = useState(0)
  const levelRef = useRef(level)
  levelRef.current = level
  // 戳一下：每次换一种小动作，连点时引擎会忽略太快的第二下。
  const poke = useTapCycle(markRef)
  useEffect(() => {
    if (!pokeRef) return
    pokeRef.current = () => { poke() }
    return () => { pokeRef.current = null }
  }, [poke, pokeRef])

  // 说完回到平常时，情绪再留一会儿。新一轮开始时情绪会被清掉，这里就不再保留。
  useEffect(() => {
    if (mood !== 'idle' || !emotion || emotion === 'neutral') { setLingering(false); return }
    setLingering(true)
    const timer = window.setTimeout(() => setLingering(false), EMOTION_LINGER_MS)
    return () => window.clearTimeout(timer)
  }, [mood, emotion])

  useEffect(() => {
    if (emotionHops(emotion)) markRef.current?.bounce(0.6)
  }, [emotion])

  // 闲着时：刚连上先伸个懒腰，一阵子没动静东张西望，再久一点犯困。有动静就重新算。
  useEffect(() => {
    const waking = wasOffline.current && mood === 'idle'
    wasOffline.current = mood === 'offline'
    setStage(waking ? 'waking' : 'rest')
    if (mood !== 'idle' || lingering) return
    const timers = [
      window.setTimeout(() => setStage('bored'), BORED_AFTER_MS),
      window.setTimeout(() => setStage('drowsy'), DROWSY_AFTER_MS),
    ]
    if (waking) timers.push(window.setTimeout(() => setStage('rest'), WAKING_MS))
    return () => timers.forEach((timer) => window.clearTimeout(timer))
  }, [mood, lingering])

  // 说话时按音量画声浪。只在数值变了时更新，安静时不重画。
  useEffect(() => {
    if (mood !== 'speaking') { setMeter(0); return }
    let frame = 0
    let last = 0
    const tick = (now: number) => {
      frame = requestAnimationFrame(tick)
      if (now - last < 1000 / METER_FPS) return
      last = now
      const value = Math.round(Math.min(1, Math.max(0, levelRef.current?.() ?? 0)) * 20) / 20
      setMeter((current) => current === value ? current : value)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [mood])

  const hud: HudSpec | null = mood === 'speaking' ? { kind: 'voice', level: meter, mode: 'speaking' } : null

  return <div className={`character mowa mood-${mood}`} aria-hidden="true">
    <CharacterMark
      ref={markRef}
      {...drawing}
      state={demoState ?? mowaState(mood, emotion, lingering, stage)}
      hud={hud}
      antics={lively}
      // 眼睛跟着鼠标（手机上是手指）转。
      followPointer
      emphasis={emphasis}
      sizeCss="min(78cqw, 72cqh)"
      className="mowa-mark"
    />
  </div>
}
