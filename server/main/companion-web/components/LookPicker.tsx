'use client'

import dynamic from 'next/dynamic'
import type { ReactNode } from 'react'
import { ArrowLeft, Check } from 'lucide-react'
import { LOOK_COLORS, LOOK_KINDS, SAMPLE_LIVE2D_MODELS, type CustomLook, type RoleOutfit } from '../lib/roles'
import type { CharacterStyle } from '../lib/characterStyle'

// Mowa Bot 的选项只在挑 Mowa Bot 时才下载。
const MowaLookPicker = dynamic(() => import('./MowaLookPicker'), { ssr: false })

interface LookPickerProps {
  /** 页面上方钉住的预览（和表情按钮），由角色设置画好传进来。 */
  stage: ReactNode
  /** 预设角色的几套装扮。有装扮时这一页只换装扮。 */
  outfits: RoleOutfit[]
  outfit?: string
  onOutfit: (key: string) => void
  /** 自己做的角色的形象：手绘玩偶或 Mowa Bot，以及光的颜色。 */
  look: CustomLook
  onLook: (look: CustomLook) => void
  onDrawn: () => void
  onMowa: () => void
  style: CharacterStyle
  onClose: () => void
}

/**
 * 形象选择页：和声音选择页一样，盖在角色设置上面的一整页。
 *
 * 不管是哪种角色，入口都是角色设置里的“形象”一行，点开都是这一页，上面钉着预览，下面是能挑的东西：
 * 有几套装扮的预设角色（例如年糕）挑装扮；自己做的角色挑手绘玩偶或 Mowa Bot，再挑光的颜色。
 * 挑的时候预览马上跟着变，回到角色设置点保存才算数。
 */
export default function LookPicker({ stage, outfits, outfit, onOutfit, look, onLook, onDrawn, onMowa, style, onClose }: LookPickerProps) {
  const change = (patch: Partial<CustomLook>) => onLook({ ...look, ...patch })
  return <div className="voice-picker look-picker" role="dialog" aria-modal="true" aria-label="选择形象">
    <header className="editor-bar voice-picker-bar">
      <button type="button" className="glass-button" onClick={onClose} aria-label="返回角色设置"><ArrowLeft size={22} /></button>
      <div className="voice-picker-title">
        <h1>选择形象</h1>
        <p>挑好后回到角色设置点保存</p>
      </div>
      <button type="button" className="button primary" onClick={onClose}>完成</button>
    </header>

    <div className="look-picker-body">
      <div className="look-picker-stage">{stage}</div>

      <div className="look-picker-options">
        {outfits.length > 0 ? <section className="mowa-section">
          <p className="mowa-section-title">装扮</p>
          <div className="look-cards" role="radiogroup" aria-label="装扮">
            {outfits.map((item) => <button
              key={item.key} type="button" role="radio" aria-checked={outfit === item.key} className="look-card"
              aria-label={item.label} onClick={() => onOutfit(item.key)}
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              {item.look.body && <img src={item.look.body} alt="" draggable={false} />}
              <span>{item.label}{outfit === item.key && <Check size={14} aria-hidden="true" />}</span>
            </button>)}
          </div>
          <p className="field-note">装扮记在这台设备上，换设备会回到默认的样子。</p>
        </section> : <>
          {style === 'live2d' && <section className="mowa-section">
            <p className="mowa-section-title">模型</p>
            <div className="choice-row" role="radiogroup" aria-label="模型">
              {SAMPLE_LIVE2D_MODELS.map((item) => <button
                key={item.key} type="button" role="radio" aria-checked={(look.model ?? SAMPLE_LIVE2D_MODELS[0].key) === item.key} className="choice"
                onClick={() => change({ model: item.key })}
              >{item.label}</button>)}
            </div>
          </section>}

          {style !== 'live2d' && <div className="look-tabs" role="radiogroup" aria-label="形象类型">
            <button type="button" role="radio" aria-checked={!look.mowa} onClick={onDrawn}>手绘玩偶</button>
            <button type="button" role="radio" aria-checked={Boolean(look.mowa)} onClick={onMowa}>Mowa Bot</button>
          </div>}

          {style !== 'live2d' && !look.mowa && <section className="mowa-section">
            <p className="mowa-section-title">动物</p>
            <div className="look-cards" role="radiogroup" aria-label="动物">
              {LOOK_KINDS.map((kind) => <button
                key={kind.key} type="button" role="radio" aria-checked={look.kind === kind.key} className="look-card"
                aria-label={kind.label} onClick={() => change({ kind: kind.key })}
              >
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={kind.image} alt="" draggable={false} />
                <span>{kind.label}{look.kind === kind.key && <Check size={14} aria-hidden="true" />}</span>
              </button>)}
            </div>
          </section>}

          {style !== 'live2d' && look.mowa && <MowaLookPicker look={look} onChange={onLook} />}

          <section className="mowa-section">
            <p className="mowa-section-title">光的颜色</p>
            <div className="swatch-row" role="radiogroup" aria-label="颜色">
              {LOOK_COLORS.map((color) => <button
                key={color.key} type="button" role="radio" aria-checked={look.color === color.key} aria-label={color.label} title={color.label}
                className="swatch" style={{ background: color.plush.fur }}
                onClick={() => change({ color: color.key })}
              />)}
            </div>
            <p className="field-note">{style === '3d' ? '' : '颜色是和它通话时页面的光。'}形象记在这台设备上，换设备会回到默认的样子。</p>
          </section>
        </>}
      </div>
    </div>
  </div>
}
