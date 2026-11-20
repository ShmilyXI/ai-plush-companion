'use client'

import { useEffect, useState } from 'react'
import { roleLook } from '../lib/roles'

interface AvatarProps {
  name: string
  size?: number
  /** 用会动的头像（如果这个角色有）。只在语音通话的大头像上用。 */
  motion?: boolean
  className?: string
}

/** 角色头像。名字总是写在旁边，所以头像本身不朗读。 */
export default function Avatar({ name, size = 40, motion = false, className = '' }: AvatarProps) {
  const look = roleLook(name)
  // 会动的头像加载失败，或者用户在系统里选了“减少动态效果”，就用静态的。
  const [still, setStill] = useState(false)
  useEffect(() => {
    setStill(motion && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  }, [motion, name])
  const source = (motion && !still && look.motion) || look.avatar
  return <span
    className={`avatar ${className}`.trim()} aria-hidden="true"
    style={{ width: size, height: size, fontSize: Math.round(size * 0.42), background: look.tint }}
  >
    {source
      // eslint-disable-next-line @next/next/no-img-element
      ? <img src={source} alt="" width={size} height={size} draggable={false} onError={() => setStill(true)} />
      : name.trim().slice(0, 1) || '·'}
  </span>
}
