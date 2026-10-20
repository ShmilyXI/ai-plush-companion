import { DeleteOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons'
import { Button, Input, List, Select, Space, Tag, Typography, Upload } from 'antd'
import { SCREEN_STATES, type SceneLayer, type ScreenScene } from '../../screen-composer/sceneTypes'
import { stateLabels } from '../../screen-composer/labels'
import { EXPRESSIONS, expressionName } from '../../screen-composer/expressions'
import { Field, SectionTitle } from './Field'

interface Props {
  scene: ScreenScene
  selectedLayer: string | null
  selectedTouch: string | null
  imageUploadBusy: boolean
  issueCount: number
  onScene: (scene: ScreenScene) => void
  onSelectLayer: (id: string) => void
  onSelectTouch: (id: string) => void
  onAddLayer: (type: SceneLayer['type']) => void
  onAddTouch: () => void
  onUploadImage: (file: File) => Promise<boolean>
}

function layerTitle(layer: SceneLayer) {
  if (layer.type === 'text') return `文字 · ${layer.text || '（空）'}`
  if (layer.type === 'animation') {
    const name = expressionName(layer.resource)
    return `表情 · ${EXPRESSIONS.find((item) => item.name === name)?.label ?? name}`
  }
  return `图片 · ${(layer.resource || '').replace(/^screen-asset:/, '').slice(0, 8) || '未选择'}`
}

/** Layers and touch regions of the scene; saved and published to the device. */
export function LayersPanel({ scene, selectedLayer, selectedTouch, imageUploadBusy, issueCount, onScene, onSelectLayer, onSelectTouch, onAddLayer, onAddTouch, onUploadImage }: Props) {
  const selected = scene.layers.find((layer) => layer.id === selectedLayer)
  const patchLayer = (id: string, patch: Partial<SceneLayer>) => onScene({ ...scene, layers: scene.layers.map((item) => item.id === id ? { ...item, ...patch } : item) })
  return <div className="screen-composer-fields">
    <SectionTitle extra={<Space size={4} wrap>
      <Button size="small" icon={<PlusOutlined />} onClick={() => onAddLayer('image')}>图片</Button>
      <Button size="small" icon={<PlusOutlined />} onClick={() => onAddLayer('text')}>文字</Button>
      <Button size="small" icon={<PlusOutlined />} onClick={() => onAddLayer('animation')}>表情</Button>
      <Upload showUploadList={false} beforeUpload={onUploadImage} disabled={imageUploadBusy}><Button size="small" loading={imageUploadBusy} icon={<UploadOutlined />}>上传图片</Button></Upload>
    </Space>}>图层</SectionTitle>
    <List size="small" dataSource={scene.layers} locale={{ emptyText: '暂无图层' }} renderItem={(layer) =>
      <List.Item className={layer.id === selectedLayer ? 'screen-composer-selected' : ''} onClick={() => onSelectLayer(layer.id)}
        actions={[<Button key="delete" type="text" size="small" aria-label="删除图层" icon={<DeleteOutlined />} onClick={(event) => { event.stopPropagation(); onScene({ ...scene, layers: scene.layers.filter((item) => item.id !== layer.id) }) }} />]}>
        <span className="screen-composer-layer-title">{layerTitle(layer)}</span>
        <Tag>{layer.visibleWhen?.state ? stateLabels[layer.visibleWhen.state] : '始终显示'}</Tag>
      </List.Item>} />

    {selected && <div className="screen-composer-editor">
      <SectionTitle>当前图层</SectionTitle>
      {selected.type === 'text' && <Field label="文字内容"><Input value={selected.text} onChange={(event) => patchLayer(selected.id, { text: event.target.value })} /></Field>}
      {selected.type === 'image' && <Field label="图片资源" hint="上传后自动填入"><Input value={selected.resource} onChange={(event) => patchLayer(selected.id, { resource: event.target.value })} /></Field>}
      {selected.type === 'animation' && <Field label="表情"><Select value={expressionName(selected.resource)} onChange={(name) => patchLayer(selected.id, { resource: `assets/${name}.eaf` })} options={EXPRESSIONS.map((item) => ({ value: item.name, label: `${item.label}（${item.name}）` }))} style={{ width: '100%' }} /></Field>}
      <Field label="显示条件" hint="设备处于该状态时才显示"><Select allowClear placeholder="始终显示" value={selected.visibleWhen?.state} onChange={(state) => patchLayer(selected.id, { visibleWhen: state ? { state } : undefined })} options={SCREEN_STATES.map((state) => ({ value: state, label: stateLabels[state] }))} style={{ width: '100%' }} /></Field>
    </div>}

    <SectionTitle extra={<Button size="small" icon={<PlusOutlined />} onClick={onAddTouch}>添加</Button>}>触摸热区</SectionTitle>
    <List size="small" dataSource={scene.touchRegions} locale={{ emptyText: '暂无触摸热区' }} renderItem={(region) =>
      <List.Item className={region.id === selectedTouch ? 'screen-composer-selected' : ''} onClick={() => onSelectTouch(region.id)}
        actions={[<Button key="delete" type="text" size="small" aria-label="删除热区" icon={<DeleteOutlined />} onClick={(event) => { event.stopPropagation(); onScene({ ...scene, touchRegions: scene.touchRegions.filter((item) => item.id !== region.id) }) }} />]}>
        <span className="screen-composer-layer-title">{region.action.kind === 'device_tool' ? region.action.name : region.action.topic}</span>
      </List.Item>} />
    {issueCount > 0 && <Typography.Text type="danger">当前有 {issueCount} 个校验问题</Typography.Text>}
  </div>
}
