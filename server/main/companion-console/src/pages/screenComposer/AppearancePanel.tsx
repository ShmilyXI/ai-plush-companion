import { Button, ColorPicker, Segmented, Select, Slider, Space, Switch, Typography } from 'antd'
import { CHARACTERS, playEffect } from '../../screen-composer/expressionEngines'
import { BALL_SHAPES, DEFAULT_APPEARANCE, EYE_SCALE_RANGE, type Appearance } from '../../screen-composer/appearance'
import { Field, SectionTitle } from './Field'

interface Props {
  appearance: Appearance
  onChange: (patch: Partial<Appearance>) => void
}

function SliderField({ label, hint, value, min, max, step, format, onChange }: {
  label: string; hint?: string; value: number; min: number; max: number; step: number; format: (value: number) => string; onChange: (value: number) => void
}) {
  return <Field label={label} hint={hint}>
    <div className="screen-composer-slider-row">
      <Slider aria-label={label} min={min} max={max} step={step} value={value} onChange={onChange} />
      <Typography.Text type="secondary" className="screen-composer-slider-value">{format(value)}</Typography.Text>
    </div>
  </Field>
}

const signed = (value: number) => (value > 0 ? `+${value.toFixed(1)}` : value.toFixed(1))

/** How the expression character looks in the web preview only; none of this is saved to the scene or sent to the device. */
export function AppearancePanel({ appearance, onChange }: Props) {
  const isBall = appearance.character === 'ball'
  return <div className="screen-composer-fields">
    <SectionTitle>形象</SectionTitle>
    <Field label="表情形象"><Select value={appearance.character} onChange={(character) => onChange({ character })} options={CHARACTERS.map((item) => ({ value: item.id, label: item.label }))} style={{ width: '100%' }} /></Field>
    <Field label="体型" hint={isBall ? undefined : '仅球球支持'}><Segmented disabled={!isBall} value={appearance.shape} onChange={(shape) => onChange({ shape })} options={BALL_SHAPES.map((item) => ({ value: item.id, label: item.label }))} /></Field>
    <Field label="自定义配色" hint="关闭时用默认色">
      <Space>
        <Switch aria-label="自定义配色" checked={appearance.customColor} onChange={(customColor) => onChange({ customColor })} />
        {appearance.customColor && <>
          <ColorPicker value={appearance.color} disabledAlpha onChangeComplete={(color) => onChange({ color: color.toHexString() })} />
          <Typography.Text type="secondary">体色</Typography.Text>
          <ColorPicker value={appearance.eyeColor} disabledAlpha onChangeComplete={(color) => onChange({ eyeColor: color.toHexString() })} />
          <Typography.Text type="secondary">眼色</Typography.Text>
        </>}
      </Space>
    </Field>

    <SectionTitle>眼睛</SectionTitle>
    <SliderField label="眼睛大小" hint="小屏建议 ≥1.5" {...EYE_SCALE_RANGE} value={appearance.eyeScale} format={(value) => `${value.toFixed(1)}×`} onChange={(eyeScale) => onChange({ eyeScale })} />
    <SliderField label="视线左右" hint="负偏左，正偏右" min={-1} max={1} step={0.1} value={appearance.gazeX} format={signed} onChange={(gazeX) => onChange({ gazeX })} />
    <SliderField label="视线上下" hint="负偏上，正偏下" min={-1} max={1} step={0.1} value={appearance.gazeY} format={signed} onChange={(gazeY) => onChange({ gazeY })} />

    <SectionTitle>效果</SectionTitle>
    <Field label="线稿模式" hint="只画轮廓线"><Switch aria-label="线稿模式" checked={appearance.sketch} onChange={(sketch) => onChange({ sketch })} /></Field>
    <Field label="精简特效" hint="关闭彩带和粒子"><Switch aria-label="精简特效" checked={appearance.lite} onChange={(lite) => onChange({ lite })} /></Field>
    <Field label="自动休眠" hint="无变化则待机/睡眠"><Switch aria-label="自动休眠" checked={appearance.idle} onChange={(idle) => onChange({ idle })} /></Field>
    <Field label="试一下特效"><Space wrap>
      <Button size="small" onClick={() => playEffect('spin')}>自旋</Button>
      <Button size="small" onClick={() => playEffect('burst')}>撒花</Button>
      <Button size="small" onClick={() => playEffect('bounce')}>弹跳</Button>
    </Space></Field>

    <Button className="screen-composer-reset" size="small" onClick={() => onChange({ ...DEFAULT_APPEARANCE })}>恢复默认外观</Button>
  </div>
}
