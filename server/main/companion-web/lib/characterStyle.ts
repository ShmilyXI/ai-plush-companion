import { customLook, hasCustomLive2D } from './roles'

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
 *
 * 放在这里而不是 RoleCharacter 里：头像和登录页也要知道一个角色是不是用 Mowa Bot 画的，
 * 但不该因此加载 three.js 和 Live2D。
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

/** 头像和静态的角色图要不要画成 Mowa Bot：和会动的角色用同一种画法，两边才一致。 */
export function drawsWithMowa(name: string) {
  return characterKind(name) === 'mowa'
}
