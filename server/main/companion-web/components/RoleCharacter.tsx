'use client'

import dynamic from 'next/dynamic'
import Character, { type CharacterMood } from './Character'
import Live2DCharacter from './Live2DCharacter'
import Puppet from './Puppet'
import { customLook, hasCustomLive2D } from '../lib/roles'
import type { ReplyEmotion } from '../lib/mowa'

// Mowa Bot 只在有角色用它时才下载。
const MowaCharacter = dynamic(() => import('./MowaCharacter'), { ssr: false })

export type CharacterStyle = 'none' | 'auto' | 'live2d' | '2d' | '3d' | 'mowa'

/**
 * 角色用哪种方式画出来。由环境变量 NEXT_PUBLIC_CHARACTER_STYLE 决定：
 *
 * - 不设（默认）：每个角色用自己的 2D 图（components/Puppet.tsx），会眨眼、对口型。
 *   有为它定制的 Live2D 模型的角色（lib/roles.ts 里标了 custom 的模型）用 Live2D；
 *   用户在角色设置里挑了 Mowa Bot 角色的，用 Mowa Bot。
 * - live2d：所有角色都用 Live2D（components/Live2DCharacter.tsx），没有定制模型的用官方示例模型。模型文件要先用 scripts/fetch-live2d.py 下载。
 * - 2d：所有角色都用 2D 图。
 * - mowa：所有角色都用 Mowa Bot（components/MowaCharacter.tsx），兔子、猫、小熊画成同样颜色的 Mowa Bot 造型，表情跟着回复的情绪变。
 * - none：不显示会动的角色，通话时只有底部的通话栏。
 * - 3d：用 three.js 搭的角色（components/Character.tsx），保留着，默认不用。
 */
export function characterStyle(): CharacterStyle {
  const style = process.env.NEXT_PUBLIC_CHARACTER_STYLE
  return style === 'none' || style === 'live2d' || style === '2d' || style === '3d' || style === 'mowa' ? style : 'auto'
}

/** 这个角色实际用哪种方式画。 */
export function characterKind(name: string): Exclude<CharacterStyle, 'auto'> {
  const style = characterStyle()
  if (style !== 'auto') return style
  if (customLook(name)?.mowa) return 'mowa'
  return hasCustomLive2D(name) ? 'live2d' : '2d'
}

interface RoleCharacterProps {
  name: string
  mood: CharacterMood
  level?: () => number
  /** false 表示角色缩在角落里，可以少做些逐帧的动画。 */
  lively?: boolean
  /** 这一轮回复的情绪。目前只有 Mowa Bot 角色会跟着变表情。 */
  emotion?: ReplyEmotion | null
}

export default function RoleCharacter({ emotion, ...props }: RoleCharacterProps) {
  const style = characterKind(props.name)
  if (style === '3d') return <Character name={props.name} mood={props.mood} level={props.level} />
  if (style === '2d') return <Puppet {...props} />
  if (style === 'live2d') return <Live2DCharacter name={props.name} mood={props.mood} level={props.level} />
  if (style === 'mowa') return <MowaCharacter {...props} emotion={emotion} />
  return null
}
