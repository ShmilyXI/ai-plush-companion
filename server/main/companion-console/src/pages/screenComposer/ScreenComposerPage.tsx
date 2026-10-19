import { DeleteOutlined, HolderOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons'
import { AdminPage } from '../admin/AdminPage'
import { Button, Card, Divider, Input, InputNumber, List, Select, Space, Tag, Typography, Upload, message } from 'antd'
import { useMemo, useState } from 'react'
import { SCREEN_MODES, SCREEN_STATES, type SceneLayer, type ScreenMode, type ScreenScene, type TouchRegion } from '../../screen-composer/sceneTypes'
import { validateScreenScene } from '../../screen-composer/sceneValidation'
import { publishScreenScene, saveScreenScene } from '../../api/screenScenes'
import './screenComposer.css'

const modeLabels: Record<ScreenMode, string> = { static_image: '静态图', chat: '对话', emotion_chat: '表情对话', text_chat: '纯文字对话', custom: '自定义展示' }

const initialScene: ScreenScene = {
  schemaVersion: 1, sceneId: 'esp-vocat-default', version: 1, mode: 'emotion_chat',
  canvas: { width: 360, height: 360, shape: 'circle', safeInset: 24 }, layers: [], touchRegions: [], resources: {},
}

export function ScreenComposerPage() {
  const [scene, setScene] = useState<ScreenScene>(() => {
    try { return JSON.parse(localStorage.getItem('screen-composer-scene') || '') as ScreenScene } catch { return initialScene }
  })
  const [selectedLayer, setSelectedLayer] = useState<string | null>(null)
  const [selectedTouch, setSelectedTouch] = useState<string | null>(null)
  const [deviceId, setDeviceId] = useState(() => localStorage.getItem('screen-composer-device-id') || '')
  const [publishedId, setPublishedId] = useState<string | null>(null)
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
  const selected = scene.layers.find((layer) => layer.id === selectedLayer)
  return <AdminPage title="屏幕编排器" subTitle="为不同设备编排画布、状态内容、表情和触摸动作" actions={<Space><Button onClick={save}>保存草稿</Button><Button type="primary" onClick={publish}>发布版本</Button></Space>}>
    <div className="screen-composer-grid">
      <Card title="画布预览" className="screen-composer-canvas-card">
        <div className="screen-composer-stage"><div className={`screen-composer-canvas ${scene.canvas.shape}`} style={{ aspectRatio: `${scene.canvas.width} / ${scene.canvas.height}` }}>
          {scene.layers.map((layer) => <div key={layer.id} className="screen-composer-layer" style={{ left: `${layer.x / scene.canvas.width * 100}%`, top: `${layer.y / scene.canvas.height * 100}%`, width: `${layer.width / scene.canvas.width * 100}%`, height: `${layer.height / scene.canvas.height * 100}%`, opacity: layer.opacity }} onClick={() => setSelectedLayer(layer.id)}>{layer.type === 'text' ? layer.text : layer.resource}</div>)}
          {scene.touchRegions.map((region) => <div key={region.id} className="screen-composer-touch" style={{ left: `${region.x / scene.canvas.width * 100}%`, top: `${region.y / scene.canvas.height * 100}%`, width: `${region.width / scene.canvas.width * 100}%`, height: `${region.height / scene.canvas.height * 100}%` }} onClick={() => setSelectedTouch(region.id)}>触摸</div>)}
        </div></div>
        <Divider />
        <Space wrap><Typography.Text>设备 ID</Typography.Text><Input placeholder="绑定设备 ID" value={deviceId} onChange={(event) => setDeviceId(event.target.value)} style={{ width: 220 }} /><Typography.Text>模式</Typography.Text><Select value={scene.mode} onChange={(mode) => setScene({ ...scene, mode })} options={SCREEN_MODES.map((mode) => ({ value: mode, label: modeLabels[mode] }))} /><Typography.Text>形状</Typography.Text><Select value={scene.canvas.shape} onChange={(shape) => updateCanvas({ shape })} options={[{ value: 'circle', label: '圆形' }, { value: 'rect', label: '矩形' }]} /></Space>
        <Space className="screen-composer-dimensions"><InputNumber addonBefore="宽" min={1} max={4096} value={scene.canvas.width} onChange={(width) => updateCanvas({ width: width || 1 })} /><InputNumber addonBefore="高" min={1} max={4096} value={scene.canvas.height} onChange={(height) => updateCanvas({ height: height || 1 })} /><InputNumber addonBefore="安全边距" min={0} max={180} value={scene.canvas.safeInset} onChange={(safeInset) => updateCanvas({ safeInset: safeInset || 0 })} /></Space>
      </Card>
      <Card title="图层与触摸" className="screen-composer-side-card">
        <Space wrap><Button icon={<PlusOutlined />} onClick={() => addLayer('image')}>图片</Button><Button icon={<PlusOutlined />} onClick={() => addLayer('text')}>文字</Button><Button icon={<PlusOutlined />} onClick={() => addLayer('animation')}>动画</Button><Button onClick={addTouch}>触摸热区</Button><Upload showUploadList={false}><Button icon={<UploadOutlined />}>上传资源</Button></Upload></Space>
        <List size="small" dataSource={scene.layers} locale={{ emptyText: '暂无图层' }} renderItem={(layer) => <List.Item className={layer.id === selectedLayer ? 'screen-composer-selected' : ''} onClick={() => setSelectedLayer(layer.id)} actions={[<Button key="delete" type="text" icon={<DeleteOutlined />} onClick={() => setScene({ ...scene, layers: scene.layers.filter((item) => item.id !== layer.id) })} />]}><HolderOutlined /> <span>{layer.type === 'text' ? layer.text : layer.resource}</span><Tag>{layer.visibleWhen?.state || '固定'}</Tag></List.Item>} />
        <Typography.Title level={5}>触摸热区</Typography.Title>
        <List size="small" dataSource={scene.touchRegions} locale={{ emptyText: '暂无触摸热区' }} renderItem={(region) => <List.Item className={region.id === selectedTouch ? 'screen-composer-selected' : ''} onClick={() => setSelectedTouch(region.id)} actions={[<Button key="delete" type="text" icon={<DeleteOutlined />} onClick={() => setScene({ ...scene, touchRegions: scene.touchRegions.filter((item) => item.id !== region.id) })} />]}>{region.action.kind === 'device_tool' ? region.action.name : region.action.topic}</List.Item>} />
        {selected && <div className="screen-composer-editor"><Typography.Title level={5}>图层属性</Typography.Title><Input value={selected.type === 'text' ? selected.text : selected.resource} onChange={(event) => setScene({ ...scene, layers: scene.layers.map((item) => item.id === selected.id ? (item.type === 'text' ? { ...item, text: event.target.value } : { ...item, resource: event.target.value }) : item) })} /><Select className="screen-composer-state" allowClear placeholder="显示状态" value={selected.visibleWhen?.state} onChange={(state) => setScene({ ...scene, layers: scene.layers.map((item) => item.id === selected.id ? { ...item, visibleWhen: state ? { state } : undefined } : item) })} options={SCREEN_STATES.map((state) => ({ value: state, label: state }))} /></div>}
        {issues.length > 0 && <Typography.Text type="danger">当前有 {issues.length} 个校验问题</Typography.Text>}
      </Card>
    </div>
  </AdminPage>
}
