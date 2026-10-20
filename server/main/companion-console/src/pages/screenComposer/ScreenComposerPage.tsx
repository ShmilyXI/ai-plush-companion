import { AdminPage } from '../admin/AdminPage'
import { Button, Card, Segmented, Space, Tag, Typography, message } from 'antd'
import { useMemo, useState } from 'react'
import { SCREEN_STATES, type SceneLayer, type ScreenState, type ScreenScene, type TouchRegion } from '../../screen-composer/sceneTypes'
import { validateScreenScene } from '../../screen-composer/sceneValidation'
import { publishScreenScene, saveScreenScene, uploadScreenImage } from '../../api/screenScenes'
import { stateLabels } from '../../screen-composer/labels'
import { createDefaultScene } from '../../screen-composer/defaultScene'
import { loadAppearance, saveAppearance, type Appearance } from '../../screen-composer/appearance'
import { ScreenImagePreview } from './ScreenImagePreview'
import { ExpressionPreview } from './ExpressionPreview'
import { SceneSettingsPanel } from './SceneSettingsPanel'
import { LayersPanel } from './LayersPanel'
import { AppearancePanel } from './AppearancePanel'
import './screenComposer.css'

function LayerContent({ layer, appearance }: { layer: SceneLayer; appearance: Appearance }) {
  if (layer.type === 'image') return <ScreenImagePreview resource={layer.resource} />
  if (layer.type === 'animation') return <ExpressionPreview resource={layer.resource} appearance={appearance} />
  return <span className="screen-composer-text">{layer.text}</span>
}

export function ScreenComposerPage() {
  const [scene, setScene] = useState<ScreenScene>(() => {
    try { return JSON.parse(localStorage.getItem('screen-composer-scene') || '') as ScreenScene } catch { return createDefaultScene() }
  })
  const [selectedLayer, setSelectedLayer] = useState<string | null>(null)
  const [selectedTouch, setSelectedTouch] = useState<string | null>(null)
  const [deviceId, setDeviceId] = useState(() => localStorage.getItem('screen-composer-device-id') || '')
  const [publishedId, setPublishedId] = useState<string | null>(null)
  const [previewState, setPreviewState] = useState<ScreenState>('idle')
  const [appearance, setAppearance] = useState<Appearance>(loadAppearance)
  const updateAppearance = (patch: Partial<Appearance>) => setAppearance((current) => { const next = { ...current, ...patch }; saveAppearance(next); return next })
  const [imageUploadBusy, setImageUploadBusy] = useState(false)
  const issues = useMemo(() => validateScreenScene(scene), [scene])
  const updateCanvas = (patch: Partial<ScreenScene['canvas']>) => setScene((current) => ({ ...current, canvas: { ...current.canvas, ...patch } }))
  const addLayer = (type: SceneLayer['type']) => {
    const id = `layer-${Date.now()}`
    const layer: SceneLayer = { id, type, text: type === 'text' ? '新文字' : undefined, resource: type !== 'text' ? 'assets/neutral.eaf' : undefined, x: 24, y: 24, width: 312, height: 312, opacity: 1 }
    setScene((current) => ({ ...current, layers: [...current.layers, layer] })); setSelectedLayer(id)
  }
  const addTouch = () => {
    const id = `touch-${Date.now()}`
    const region: TouchRegion = { id, shape: 'rect', x: 24, y: 24, width: 96, height: 72, action: { kind: 'device_tool', name: 'self.get_device_status', arguments: {} } }
    setScene((current) => ({ ...current, touchRegions: [...current.touchRegions, region] })); setSelectedTouch(id)
  }
  const save = async () => {
    localStorage.setItem('screen-composer-scene', JSON.stringify(scene))
    if (deviceId.trim()) {
      localStorage.setItem('screen-composer-device-id', deviceId.trim())
      const saved = await saveScreenScene(deviceId.trim(), scene)
      setPublishedId(saved.id)
    }
    message.success('场景草稿已保存')
  }
  const publish = async () => {
    const errors = validateScreenScene(scene)
    if (errors.length) { message.error(`还有 ${errors.length} 个问题需要修正`); return }
    localStorage.setItem('screen-composer-published', JSON.stringify({ ...scene, version: scene.version + 1 }))
    if (publishedId) await publishScreenScene(publishedId)
    message.success('场景版本已发布')
  }
  const uploadImage = async (file: File) => {
    setImageUploadBusy(true)
    try {
      const uploaded = await uploadScreenImage(file, scene.canvas.width, scene.canvas.height)
      // Store the resource id, not a URL: the server builds the device download URL from
      // server.ota at publish time, so it never depends on the host the console was opened on.
      const id = `layer-${Date.now()}`
      setScene((current) => ({ ...current, layers: [...current.layers, { id, type: 'image', resource: uploaded.resource, x: 0, y: 0, width: current.canvas.width, height: current.canvas.height, opacity: 1 }] }))
      setSelectedLayer(id)
      message.success('图片已转换并加入场景')
    } finally { setImageUploadBusy(false) }
    return false
  }
  const visibleLayers = scene.layers.filter((layer) => (!layer.visibleWhen?.state || layer.visibleWhen.state === previewState) && (!layer.visibleWhen?.mode || layer.visibleWhen.mode === scene.mode))
  const { canvas } = scene
  const pct = (value: number, total: number) => `${value / total * 100}%`
  return <AdminPage title="屏幕编排器" subTitle="为不同设备编排画布、状态内容、表情和触摸动作" actions={<Space><Button onClick={save}>保存草稿</Button><Button type="primary" onClick={publish}>发布版本</Button></Space>}>
    <div className="screen-composer-grid">
      <Card title="画布预览" className="screen-composer-canvas-card" extra={<Space size={8}><Typography.Text type="secondary">模拟设备状态</Typography.Text><Segmented size="small" value={previewState} onChange={(state) => setPreviewState(state as ScreenState)} options={SCREEN_STATES.map((state) => ({ value: state, label: stateLabels[state] }))} /></Space>}>
        <div className="screen-composer-stage"><div className={`screen-composer-canvas ${canvas.shape}`} style={{ aspectRatio: `${canvas.width} / ${canvas.height}` }}>
          {visibleLayers.map((layer) => <div key={layer.id} className={`screen-composer-layer ${layer.type}${layer.id === selectedLayer ? ' selected' : ''}`} style={{ left: pct(layer.x, canvas.width), top: pct(layer.y, canvas.height), width: pct(layer.width, canvas.width), height: pct(layer.height, canvas.height), opacity: layer.opacity }} onClick={() => setSelectedLayer(layer.id)}><LayerContent layer={layer} appearance={appearance} /></div>)}
          {(scene.mode === 'chat' || scene.mode === 'emotion_chat' || scene.mode === 'text_chat') && <div className="screen-composer-caption" style={{ bottom: `${canvas.safeInset / canvas.height * 100 + 6}%` }}>对话字幕（示意）</div>}
          {canvas.safeInset > 0 && <div className={`screen-composer-safe ${canvas.shape}`} style={{ inset: `${canvas.safeInset / canvas.height * 100}% ${canvas.safeInset / canvas.width * 100}%` }} />}
          {scene.touchRegions.map((region) => <div key={region.id} className={`screen-composer-touch${region.id === selectedTouch ? ' selected' : ''}`} style={{ left: pct(region.x, canvas.width), top: pct(region.y, canvas.height), width: pct(region.width, canvas.width), height: pct(region.height, canvas.height) }} onClick={() => setSelectedTouch(region.id)}>触摸</div>)}
        </div></div>
        <Typography.Paragraph type="secondary" className="screen-composer-note">位置、比例和裁切与设备一致；字体渲染、表情动画细节以真机为准。虚线圈为安全边距，黄色框为触摸热区。</Typography.Paragraph>
      </Card>
      <div className="screen-composer-side">
        <Card title="场景设置" extra={<Tag color="blue">发布到设备</Tag>}>
          <SceneSettingsPanel scene={scene} deviceId={deviceId} onDeviceId={setDeviceId} onMode={(mode) => setScene((current) => ({ ...current, mode }))} onCanvas={updateCanvas} />
        </Card>
        <Card title="图层与触摸热区" extra={<Tag color="blue">发布到设备</Tag>}>
          <LayersPanel scene={scene} selectedLayer={selectedLayer} selectedTouch={selectedTouch} imageUploadBusy={imageUploadBusy} issueCount={issues.length}
            onScene={setScene} onSelectLayer={setSelectedLayer} onSelectTouch={setSelectedTouch} onAddLayer={addLayer} onAddTouch={addTouch} onUploadImage={uploadImage} />
        </Card>
        <Card title="预览外观" extra={<Tag>仅网页预览</Tag>}>
          <AppearancePanel appearance={appearance} onChange={updateAppearance} />
        </Card>
      </div>
    </div>
  </AdminPage>
}
