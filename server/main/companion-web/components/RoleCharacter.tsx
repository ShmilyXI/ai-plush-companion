'use client'

import Character, { type CharacterMood } from './Character'
import Puppet from './Puppet'

export type CharacterStyle = 'none' | '2d' | '3d'

/**
 * 通话时用哪种方式把角色画出来。由环境变量 NEXT_PUBLIC_CHARACTER_STYLE 决定：
 *
 * - 不设（默认）：不显示会动的角色，通话时只有底部的声浪。
 * - 2d：用图片做的角色，会眨眼、对口型（components/Puppet.tsx）。
 * - 3d：用 three.js 搭的角色（components/Character.tsx）。
 *
 * 两种画法的代码都保留着，只是默认不启用。以后接入 Live2D 时在这里加一种。
 */
export function characterStyle(): CharacterStyle {
  const style = process.env.NEXT_PUBLIC_CHARACTER_STYLE
  return style === '2d' || style === '3d' ? style : 'none'
}

interface RoleCharacterProps {
  name: string
  mood: CharacterMood
  level?: () => number
}

export default function RoleCharacter(props: RoleCharacterProps) {
  const style = characterStyle()
  if (style === '3d') return <Character {...props} />
  if (style === '2d') return <Puppet {...props} />
  return null
}
