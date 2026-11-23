'use client'

import Character, { type CharacterMood } from './Character'
import Live2DCharacter from './Live2DCharacter'
import Puppet from './Puppet'

export type CharacterStyle = 'none' | 'live2d' | '2d' | '3d'

/**
 * 通话时用哪种方式把角色画出来。由环境变量 NEXT_PUBLIC_CHARACTER_STYLE 决定：
 *
 * - 不设（默认）或 live2d：Live2D 模型（components/Live2DCharacter.tsx）。模型文件要先用 scripts/fetch-live2d.py 下载。
 * - none：不显示会动的角色，通话时只有底部的通话栏。
 * - 2d：用图片做的角色，会眨眼、对口型（components/Puppet.tsx）。
 * - 3d：用 three.js 搭的角色（components/Character.tsx）。
 *
 * 2d 和 3d 的代码都保留着，只是默认不启用。
 */
export function characterStyle(): CharacterStyle {
  const style = process.env.NEXT_PUBLIC_CHARACTER_STYLE
  return style === 'none' || style === '2d' || style === '3d' ? style : 'live2d'
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
  if (style === 'live2d') return <Live2DCharacter {...props} />
  return null
}
