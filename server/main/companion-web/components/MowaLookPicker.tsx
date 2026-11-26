'use client'

import { CAST, SHAPE_FAMILIES, renderStaticSvgDataUrl } from '@mowa-bot/engine'
import { useMemo, useState } from 'react'
import { Search, X } from 'lucide-react'
import { MOWA_FACES, MOWA_STYLES } from '../lib/mowa'
import { mowaDrawing } from '../lib/mowaRender'
import type { CustomLook } from '../lib/roles'

interface MowaLookPickerProps {
  look: CustomLook
  onChange: (look: CustomLook) => void
}

/** 每个 Mowa Bot 角色属于哪一类，用 Mowa Bot 自己的造型分类（圆润、水果、小动物……）。 */
const FAMILY_OF = new Map(SHAPE_FAMILIES.flatMap((family) => family.shapes.map((shape) => [shape as string, family.id])))

function styleOf(look: CustomLook) {
  const style = MOWA_STYLES.find((item) => item.key === look.mowaStyle) ?? MOWA_STYLES[0]
  const variant = style.variants.find((item) => item.key === look.mowaVariant) ?? style.variants[0]
  const face = MOWA_FACES.find((item) => item.key === (look.mowaFace ?? '')) ?? MOWA_FACES[0]
  return { style, variant, face }
}

/** 角色设置里“形象”那一行的文字：挑的是哪个 Mowa Bot 角色、什么画法。 */
export function MowaLookSummary({ look }: { look: CustomLook }) {
  const member = CAST.find((item) => item.id === look.mowa)
  const { style, variant, face } = styleOf(look)
  return <>
    <strong>{member?.name.zh ?? 'Mowa Bot'}</strong>
    <span>Mowa Bot · {style.label} · {variant.label}{face.key ? ` · ${face.label}` : ''}</span>
  </>
}

/**
 * 形象选择页里挑 Mowa Bot：先挑角色，再挑画法（画风、质感、脸型）。
 *
 * 71 个角色按造型分类，也可以搜名字和描述。缩略图按当前的画法画，换画风时一眼就能看出每个角色会变成什么样。
 * 缩略图是静态图片，几十个一起显示也不会各自跑动画；会动的是页面上的预览。
 */
export default function MowaLookPicker({ look, onChange }: MowaLookPickerProps) {
  const { style, variant, face } = styleOf(look)
  const [family, setFamily] = useState('')
  const [query, setQuery] = useState('')

  const thumbnails = useMemo(() => CAST.map((member) => {
    const { preset: _preset, styleId, ...drawing } = mowaDrawing({
      cast: member.id, shape: member.shape, ink: { flat: '#000' }, style: style.key, variant: variant.key, face: face.key || undefined,
    })
    return {
      id: member.id,
      label: member.name.zh,
      desc: member.desc.zh,
      family: FAMILY_OF.get(member.shape) ?? '',
      text: `${member.name.zh} ${member.name.en} ${member.desc.zh}`.toLowerCase(),
      src: renderStaticSvgDataUrl({ ...drawing, style: styleId, state: 'idle', sizePx: 64 }),
    }
  }), [style.key, variant.key, face.key])

  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
  const matching = thumbnails.filter((item) => words.every((word) => item.text.includes(word)))
  const shown = matching.filter((item) => !family || item.family === family)
  const families = SHAPE_FAMILIES
    .map((item) => ({ id: item.id, label: item.label.zh, count: matching.filter((member) => member.family === item.id).length }))
    .filter((item) => thumbnails.some((member) => member.family === item.id))

  const change = (patch: Partial<CustomLook>) => onChange({ ...look, ...patch })

  return <div className="mowa-picker">
    <div className="mowa-section">
      <p className="mowa-section-title">角色</p>
      <label className="voice-search mowa-search">
        <Search size={18} aria-hidden="true" />
        <input type="search" value={query} placeholder="搜名字或样子，比如“猫”“草莓”“软”" aria-label="搜索 Mowa Bot 角色" onChange={(event) => setQuery(event.target.value)} />
        {query && <button type="button" className="tool-button" aria-label="清除搜索" onClick={() => setQuery('')}><X size={16} /></button>}
      </label>
      <div className="voice-scenes" role="radiogroup" aria-label="角色分类">
        <button type="button" role="radio" aria-checked={!family} aria-label={`全部，${matching.length} 个`} onClick={() => setFamily('')}>全部<span>{matching.length}</span></button>
        {families.map((item) => <button
          key={item.id} type="button" role="radio" aria-checked={family === item.id} aria-label={`${item.label}，${item.count} 个`}
          onClick={() => setFamily(family === item.id ? '' : item.id)}
        >{item.label}<span>{item.count}</span></button>)}
      </div>
      {shown.length === 0
        ? <p className="memory-empty">没有符合的角色，换个词试试。</p>
        : <div className="mowa-cast" role="radiogroup" aria-label="Mowa Bot 角色">
          {shown.map((item) => <button
            key={item.id} type="button" role="radio" aria-checked={look.mowa === item.id} className="mowa-cast-item"
            title={item.desc} onClick={() => change({ mowa: item.id })}
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={item.src} alt="" width={64} height={64} draggable={false} />
            <span>{item.label}</span>
          </button>)}
        </div>}
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
          key={item.key} type="button" role="radio" aria-checked={variant.key === item.key} className="chip"
          onClick={() => change({ mowaStyle: style.key, mowaVariant: item.key })}
        >{item.label}</button>)}
      </div>
    </div>

    <div className="mowa-section">
      <p className="mowa-section-title">脸型</p>
      <div className="chip-row" role="radiogroup" aria-label="脸型">
        {MOWA_FACES.map((item) => <button
          key={item.key || 'own'} type="button" role="radio" aria-checked={face.key === item.key} className="chip"
          onClick={() => change({ mowaFace: item.key || undefined })}
        >{item.label}</button>)}
      </div>
    </div>
  </div>
}
