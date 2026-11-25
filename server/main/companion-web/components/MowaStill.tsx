'use client'

import { castMember, renderStaticSvgDataUrl } from '@mowa-bot/engine'
import { useMemo } from 'react'
import { mowaLook } from '../lib/mowa'

interface MowaStillProps {
  name: string
  size: number
}

/**
 * 不会动的 Mowa Bot 角色图，用在头像和角色卡片上。和会动的 MowaCharacter 用同一套造型和颜色，
 * 所以头像里的样子和通话时站出来的那个一致。
 */
export default function MowaStill({ name, size }: MowaStillProps) {
  const look = mowaLook(name)
  const member = castMember(look.cast)
  const key = member ? member.id : `${look.shape}:${JSON.stringify(look.ink)}`
  const src = useMemo(() => renderStaticSvgDataUrl(member
    ? { shape: member.shape, color: member.color, face: member.face, state: 'happy', sizePx: size }
    : { shape: look.shape, ink: look.ink, state: 'happy', sizePx: size }),
  // key 已经包含了造型和颜色；look 每次渲染都是新对象，不能放进依赖里。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  [key, size])
  // eslint-disable-next-line @next/next/no-img-element
  return <img className="mowa-still" src={src} alt="" width={size} height={size} draggable={false} />
}
