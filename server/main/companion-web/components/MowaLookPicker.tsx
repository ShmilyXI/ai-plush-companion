'use client'

import { CAST, renderStaticSvgDataUrl } from '@mowa-bot/engine'
import { useMemo, useState } from 'react'
import { MOWA_FACES, MOWA_STYLES } from '../lib/mowa'
import { mowaDrawing } from '../lib/mowaRender'
import type { CustomLook } from '../lib/roles'

interface MowaLookPickerProps {
  look: CustomLook
  onChange: (look: CustomLook) => void
}

/** 角色表收起时显示几个。选中的角色不在前面时也会显示出来。 */
const COLLAPSED = 15

/**
 * 角色设置里挑 Mowa Bot 的样子：哪个角色、什么画风、什么质感、什么脸。
 *
 * 角色表的缩略图按当前挑的画风、质感和脸画，换画风时一眼就能看出每个角色会变成什么样。
 * 缩略图是静态图片，几十个一起显示也不会各自跑动画；会动的是上面的预览。
 */
export default function MowaLookPicker({ look, onChange }: MowaLookPickerProps) {
  const style = MOWA_STYLES.find((item) => item.key === look.mowaStyle) ?? MOWA_STYLES[0]
  const variant = style.variants.find((item) => item.key === look.mowaVariant)?.key ?? style.variants[0].key
  const face = look.mowaFace ?? ''

  const thumbnails = useMemo(() => CAST.map((member) => {
    const { preset: _preset, styleId, ...drawing } = mowaDrawing({
      cast: member.id, shape: member.shape, ink: { flat: '#000' }, style: style.key, variant, face: face || undefined,
    })
    return {
      id: member.id,
      label: member.name.zh,
      desc: member.desc.zh,
      src: renderStaticSvgDataUrl({ ...drawing, style: styleId, state: 'idle', sizePx: 64 }),
    }
  }), [style.key, variant, face])

  const change = (patch: Partial<CustomLook>) => onChange({ ...look, ...patch })
  const [expanded, setExpanded] = useState(false)
  const shown = expanded ? thumbnails : thumbnails.filter((item, index) => index < COLLAPSED || item.id === look.mowa)

  return <div className="mowa-picker">
    <div className="mowa-section">
      <p className="mowa-section-title">角色</p>
      <div className="mowa-cast" role="radiogroup" aria-label="Mowa Bot 角色">
        {shown.map((item) => <button
          key={item.id} type="button" role="radio" aria-checked={look.mowa === item.id} className="mowa-cast-item"
          title={item.desc} onClick={() => change({ mowa: item.id })}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={item.src} alt="" width={64} height={64} draggable={false} />
          <span>{item.label}</span>
        </button>)}
      </div>
      {thumbnails.length > COLLAPSED && <button type="button" className="mowa-more" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
        {expanded ? '收起' : `展开全部 ${thumbnails.length} 个角色`}
      </button>}
    </div>

    <div className="mowa-section">
      <p className="mowa-section-title">画风</p>
      <div className="chip-row" role="radiogroup" aria-label="画风">
        {MOWA_STYLES.map((item) => <button
          key={item.key} type="button" role="radio" aria-checked={style.key === item.key} className="chip"
          onClick={() => change({ mowaStyle: item.key, mowaVariant: item.variants[0].key })}
        >{item.label}</button>)}
      </div>
    </div>

    <div className="mowa-section">
      <p className="mowa-section-title">质感</p>
      <div className="chip-row" role="radiogroup" aria-label="质感">
        {style.variants.map((item) => <button
          key={item.key} type="button" role="radio" aria-checked={variant === item.key} className="chip"
          onClick={() => change({ mowaStyle: style.key, mowaVariant: item.key })}
        >{item.label}</button>)}
      </div>
    </div>

    <div className="mowa-section">
      <p className="mowa-section-title">脸型</p>
      <div className="chip-row" role="radiogroup" aria-label="脸型">
        {MOWA_FACES.map((item) => <button
          key={item.key || 'own'} type="button" role="radio" aria-checked={face === item.key} className="chip"
          onClick={() => change({ mowaFace: item.key || undefined })}
        >{item.label}</button>)}
      </div>
    </div>
  </div>
}
