/**
 * 把 lib/mowa.ts 里的样子换成 Mowa Bot 能直接用的参数，并核对画风、质感和脸型在这个版本里存在。
 *
 * 这里引入了 Mowa Bot 的运行时，只给会画 Mowa Bot 的组件用（它们都是用到时才加载的）。
 */
import {
  FACE_STYLES, STYLE_IDS, acceptsVariant, castMember, getStyle,
  type CastId, type ColorId, type FaceStyle, type InkPaint, type ShapeId, type StyleId, type StyleVariant,
} from '@mowa-bot/engine'
import type { MowaLook } from './mowa'

export interface MowaDrawing {
  /** 挑的角色在这个版本里存在时是它的 id，否则为 undefined，按 shape 和 ink 画。 */
  preset?: CastId
  shape: ShapeId
  color?: ColorId
  ink?: InkPaint
  face?: FaceStyle
  styleId?: StyleId
  variant?: StyleVariant
}

export function isMowaStyle(value: string | undefined): value is StyleId {
  return Boolean(value) && (STYLE_IDS as readonly string[]).includes(value!)
}

/** 某种画风的质感是否存在。 */
export function hasVariant(style: StyleId, variant: string | undefined): variant is StyleVariant {
  return Boolean(variant) && acceptsVariant(getStyle(style), variant!)
}

/** 认得的脸型。 */
export function isMowaFace(value: string | undefined): value is FaceStyle {
  return Boolean(value) && (FACE_STYLES as readonly string[]).includes(value!)
}

/** 一个样子最后怎么画。认不出的画风、质感、脸型和角色都去掉，回到默认。 */
export function mowaDrawing(look: MowaLook): MowaDrawing {
  const member = castMember(look.cast)
  const styleId = isMowaStyle(look.style) ? look.style : undefined
  const variant = styleId && hasVariant(styleId, look.variant) ? look.variant as StyleVariant : undefined
  const face = isMowaFace(look.face) ? look.face : member?.face
  const base = member
    ? { preset: member.id, shape: member.shape, color: member.color }
    : { shape: look.shape, ink: look.ink }
  return { ...base, ...(face ? { face } : {}), ...(styleId ? { styleId } : {}), ...(variant ? { variant } : {}) }
}
