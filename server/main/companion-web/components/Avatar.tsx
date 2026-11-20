import type { CSSProperties } from 'react'
import { roleLook } from '../lib/roles'

interface AvatarProps {
  name: string
  size?: number
  className?: string
}

/** 角色头像：角色的光做底，上面是它的形象。名字总是写在旁边，所以头像本身不朗读。 */
export default function Avatar({ name, size = 40, className = '' }: AvatarProps) {
  const look = roleLook(name)
  const style = {
    width: size, height: size, fontSize: Math.round(size * 0.42),
    '--aura-1': look.aura[0], '--aura-2': look.aura[1], '--aura-3': look.aura[2],
  } as CSSProperties
  return <span className={`avatar ${className}`.trim()} aria-hidden="true" style={style}>
    {look.avatar
      // eslint-disable-next-line @next/next/no-img-element
      ? <img src={look.avatar} alt="" width={size} height={size} draggable={false} />
      : name.trim().slice(0, 1) || '·'}
  </span>
}
