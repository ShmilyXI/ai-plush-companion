'use client'

import { CharacterMark, useTapCycle, type CharacterHandle, type CharacterState } from '@mowa-bot/react'
import '@mowa-bot/react/styles.css'
import { useEffect, useRef, useState, type MutableRefObject } from 'react'
import { BORED_AFTER_MS, DROWSY_AFTER_MS, EMOTION_LINGER_MS, WAKING_MS, emotionHops, mowaLook, mowaState, type IdleStage, type ReplyEmotion } from '../lib/mowa'
import { mowaDrawing } from '../lib/mowaRender'
import type { CharacterMood } from './Character'

interface MowaCharacterProps {
  name: string
  mood: CharacterMood
  /** 角色自己声音的音量（0 到 1）。Mowa Bot 不用它：说话时嘴自己一张一合，音量由外圈的光表现（见 Pet）。 */
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

/**
 * 用 Mowa Bot 画的角色：一个由代码生成的 SVG 小角色，有几十种动画状态。
 *
 * 通话状态决定大的样子（睡、听、想），角色说话时换成这条回复的情绪，没有情绪时嘴一张一合。
 * 开心和惊讶的回复到来时跳一下。说完以后情绪还留几秒，只发文字的回复也看得到表情。
 *
 * 说话时不加 HUD（比如声浪表盘）：Mowa Bot 在 HUD 或「腰带」状态占用身体时会把整张脸淡掉，
 * 角色一开口脸就没了。
 */
export default function MowaCharacter({ name, mood, lively = true, emotion = null, demoState = null, pokeRef, emphasis = false }: MowaCharacterProps) {
  const drawing = mowaDrawing(mowaLook(name))
  const markRef = useRef<CharacterHandle>(null)
  const [lingering, setLingering] = useState(false)
  const [stage, setStage] = useState<IdleStage>('rest')
  const wasOffline = useRef(mood === 'offline')
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


  return <div className={`character mowa mood-${mood}`} aria-hidden="true">
    <CharacterMark
      ref={markRef}
      {...drawing}
      state={demoState ?? mowaState(mood, emotion, lingering, stage)}
      antics={lively}
      // 眼睛跟着鼠标（手机上是手指）转。
      followPointer
      emphasis={emphasis}
      sizeCss="min(78cqw, 72cqh)"
      className="mowa-mark"
    />
  </div>
}
