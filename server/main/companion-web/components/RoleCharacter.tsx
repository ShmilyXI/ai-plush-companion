'use client'

import dynamic from 'next/dynamic'
import Character, { type CharacterMood } from './Character'
import Live2DCharacter from './Live2DCharacter'
import Puppet from './Puppet'
import { characterKind } from '../lib/characterStyle'
import type { ReplyEmotion } from '../lib/mowa'
import type { CharacterState } from '@mowa-bot/engine'

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
}

export default function RoleCharacter({ emotion, demoState, ...props }: RoleCharacterProps) {
  const style = characterKind(props.name)
  if (style === '3d') return <Character name={props.name} mood={props.mood} level={props.level} />
  if (style === '2d') return <Puppet {...props} />
  if (style === 'live2d') return <Live2DCharacter name={props.name} mood={props.mood} level={props.level} />
  if (style === 'mowa') return <MowaCharacter {...props} emotion={emotion} demoState={demoState} />
  return null
}
