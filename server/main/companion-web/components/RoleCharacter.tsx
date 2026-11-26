'use client'

import dynamic from 'next/dynamic'
import Character, { type CharacterMood } from './Character'
import Live2DCharacter from './Live2DCharacter'
import Puppet from './Puppet'
import { characterKind } from '../lib/characterStyle'
import type { ReplyEmotion } from '../lib/mowa'
import type { CharacterState } from '@mowa-bot/engine'
import type { MutableRefObject } from 'react'

// Mowa Bot 只在有角色用它时才下载。
const MowaCharacter = dynamic(() => import('./MowaCharacter'), { ssr: false })

export { characterKind, characterStyle, type CharacterStyle } from '../lib/characterStyle'

interface RoleCharacterProps {
  name: string
  mood: CharacterMood
  level?: () => number
  /** false 表示角色缩在角落里，可以少做些逐帧的动画。 */
  lively?: boolean
  /** 这一轮回复的情绪。目前只有 Mowa Bot 角色会跟着变表情。 */
  emotion?: ReplyEmotion | null
  /** 角色设置里点着看的表情。只有 Mowa Bot 角色会演。 */
  demoState?: CharacterState | null
  /** 点了角色时调用它戳一下。Mowa Bot 和 Live2D 角色会填上。 */
  pokeRef?: MutableRefObject<(() => void) | null>
  /** 指针停在角色上。Mowa Bot 和 Live2D 角色会有反应。 */
  emphasis?: boolean
}

export default function RoleCharacter({ emotion, demoState, pokeRef, emphasis, ...props }: RoleCharacterProps) {
  const style = characterKind(props.name)
  if (style === '3d') return <Character name={props.name} mood={props.mood} level={props.level} />
  if (style === '2d') return <Puppet {...props} />
  if (style === 'live2d') return <Live2DCharacter name={props.name} mood={props.mood} level={props.level} pokeRef={pokeRef} emphasis={emphasis} />
  if (style === 'mowa') return <MowaCharacter {...props} emotion={emotion} demoState={demoState} pokeRef={pokeRef} emphasis={emphasis} />
  return null
}
