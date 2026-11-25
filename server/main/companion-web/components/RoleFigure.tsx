'use client'

import type { CSSProperties } from 'react'
import { roleLook } from '../lib/roles'
import dynamic from 'next/dynamic'
import { drawsWithMowa } from '../lib/characterStyle'

// Mowa Bot 只在有角色用它画时才下载。
const MowaStill = dynamic(() => import('./MowaStill'), { ssr: false })

/** 不会动的角色形象：角色颜色的光球，前面坐着它的玩偶。用在登录页和选角色的卡片上。 */
export default function RoleFigure({ name, size = 120 }: { name: string; size?: number }) {
  const look = roleLook(name)
  const style = {
    width: size, height: size, fontSize: size,
    '--aura-1': look.aura[0], '--aura-2': look.aura[1], '--aura-3': look.aura[2],
  } as CSSProperties
  return <span className="figure" style={style} aria-hidden="true">
    <span className="figure-orb" />
    {drawsWithMowa(name)
      ? <MowaStill name={name} size={size} />
      : look.body
      // eslint-disable-next-line @next/next/no-img-element
      ? <img src={look.body} alt="" draggable={false} />
      : <span className="figure-initial">{name.trim().slice(0, 1) || '·'}</span>}
  </span>
}
