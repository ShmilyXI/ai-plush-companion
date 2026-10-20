import { Input, InputNumber, Segmented, Select, Space } from 'antd'
import { SCREEN_MODES, type ScreenMode, type ScreenScene } from '../../screen-composer/sceneTypes'
import { modeLabels } from '../../screen-composer/labels'
import { Field } from './Field'

interface Props {
  scene: ScreenScene
  deviceId: string
  onDeviceId: (deviceId: string) => void
  onMode: (mode: ScreenMode) => void
  onCanvas: (patch: Partial<ScreenScene['canvas']>) => void
}

/** Scene settings that are saved and published to the device. */
export function SceneSettingsPanel({ scene, deviceId, onDeviceId, onMode, onCanvas }: Props) {
  const { canvas } = scene
  return <div className="screen-composer-fields">
    <Field label="目标设备" hint="保存与发布时使用"><Input placeholder="设备 ID" value={deviceId} onChange={(event) => onDeviceId(event.target.value)} allowClear /></Field>
    <Field label="显示模式"><Select value={scene.mode} onChange={onMode} options={SCREEN_MODES.map((mode) => ({ value: mode, label: modeLabels[mode] }))} style={{ width: '100%' }} /></Field>
    <Field label="画布形状"><Segmented value={canvas.shape} onChange={(shape) => onCanvas({ shape: shape as ScreenScene['canvas']['shape'] })} options={[{ value: 'circle', label: '圆形' }, { value: 'rect', label: '矩形' }]} /></Field>
    <Field label="画布尺寸"><Space>
      <InputNumber aria-label="画布宽度" addonBefore="宽" addonAfter="px" min={1} max={4096} value={canvas.width} onChange={(width) => onCanvas({ width: width || 1 })} />
      <InputNumber aria-label="画布高度" addonBefore="高" addonAfter="px" min={1} max={4096} value={canvas.height} onChange={(height) => onCanvas({ height: height || 1 })} />
    </Space></Field>
    <Field label="安全边距" hint="内容避开屏幕边缘"><InputNumber aria-label="安全边距" addonAfter="px" min={0} max={180} value={canvas.safeInset} onChange={(safeInset) => onCanvas({ safeInset: safeInset || 0 })} /></Field>
  </div>
}
