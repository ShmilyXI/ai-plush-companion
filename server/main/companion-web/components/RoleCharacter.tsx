'use client'

import Character, { type CharacterMood } from './Character'
import Puppet from './Puppet'

/**
 * 角色用哪种方式画出来。默认是用图片做的 2D 角色；
 * 把环境变量 NEXT_PUBLIC_CHARACTER_STYLE 设成 3d，就换回用 three.js 搭的 3D 角色（代码都保留着）。
 */
export const CHARACTER_STYLE: '2d' | '3d' = process.env.NEXT_PUBLIC_CHARACTER_STYLE === '3d' ? '3d' : '2d'

interface RoleCharacterProps {
  name: string
  mood: CharacterMood
  level?: () => number
}

export default function RoleCharacter(props: RoleCharacterProps) {
  return CHARACTER_STYLE === '3d' ? <Character {...props} /> : <Puppet {...props} />
}
