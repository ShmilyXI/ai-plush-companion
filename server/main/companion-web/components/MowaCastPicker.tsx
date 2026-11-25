'use client'

import { CAST, renderStaticSvgDataUrl } from '@mowa-bot/engine'
import { useMemo } from 'react'

interface MowaCastPickerProps {
  /** 选中的 cast id，没选时为 undefined。 */
  value?: string
  onChange: (id: string) => void
}

/**
 * 在角色设置里挑一个 Mowa Bot 角色。
 *
 * 缩略图是静态的 SVG 图片，几十个一起显示也不会各自跑动画。选中后预览里的那个会动。
 */
export default function MowaCastPicker({ value, onChange }: MowaCastPickerProps) {
  const items = useMemo(() => CAST.map((member) => ({
    id: member.id,
    label: member.name.zh,
    desc: member.desc.zh,
    src: renderStaticSvgDataUrl({
      shape: member.shape, color: member.color, face: member.face, nose: member.nose, state: 'happy', sizePx: 56,
    }),
  })), [])

  return <div className="mowa-cast" role="radiogroup" aria-label="Mowa Bot 角色">
    {items.map((item) => <button
      key={item.id} type="button" role="radio" aria-checked={value === item.id} className="mowa-cast-item"
      title={item.desc} onClick={() => onChange(item.id)}
    >
      <img src={item.src} alt="" width={56} height={56} draggable={false} />
      <span>{item.label}</span>
    </button>)}
  </div>
}
