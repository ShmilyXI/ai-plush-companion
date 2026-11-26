'use client'

import { renderStaticSvgDataUrl } from '@mowa-bot/engine'
import { useMemo } from 'react'
import { mowaLook } from '../lib/mowa'
import { mowaDrawing } from '../lib/mowaRender'

interface MowaStillProps {
  name: string
  size: number
}

/**
 * 不会动的 Mowa Bot 角色图，用在头像和角色卡片上。和会动的 MowaCharacter 用同一套造型、颜色、画风和脸型，
 * 所以头像里的样子和通话时站出来的那个一致。
 */
export default function MowaStill({ name, size }: MowaStillProps) {
  const drawing = mowaDrawing(mowaLook(name))
  const key = JSON.stringify(drawing)
  const src = useMemo(() => {
    const { preset: _preset, styleId, ...rest } = drawing
    return renderStaticSvgDataUrl({ ...rest, style: styleId, state: 'idle', sizePx: size })
  // key 已经包含了全部参数；drawing 每次渲染都是新对象，不能放进依赖里。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, size])
  // eslint-disable-next-line @next/next/no-img-element
  return <img className="mowa-still" src={src} alt="" width={size} height={size} draggable={false} />
}
