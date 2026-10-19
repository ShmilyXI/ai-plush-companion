import { SCREEN_MODES, SCREEN_STATES, type ScreenScene, type TouchAction } from './sceneTypes'

export interface SceneIssue { path: string; message: string }

const allowedVariables = new Set(['deviceId', 'mode', 'state', 'x', 'y'])

function numberIssue(value: unknown, path: string, min: number, max: number, issues: SceneIssue[]) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) {
    issues.push({ path, message: `必须是 ${min} 到 ${max} 之间的数字` })
  }
}

function validateAction(action: TouchAction, path: string, issues: SceneIssue[]) {
  if (!action || typeof action !== 'object' || !('kind' in action)) {
    issues.push({ path, message: '动作类型无效' }); return
  }
  if (action.kind === 'device_tool') {
    if (!action.name || action.name.includes('..') || action.name.includes('/')) issues.push({ path: `${path}.name`, message: '设备工具名无效' })
    return
  }
  if (action.kind === 'mqtt_template') {
    if (!action.topic || !action.topic.startsWith('zixuan/')) issues.push({ path: `${path}.topic`, message: 'MQTT topic 必须以 zixuan/ 开头' })
    if (action.qos !== 0 && action.qos !== 1) issues.push({ path: `${path}.qos`, message: 'QoS 只允许 0 或 1' })
    const serialized = JSON.stringify(action.payload)
    for (const match of serialized.matchAll(/\$\{([^}]+)\}/g)) {
      if (!allowedVariables.has(match[1])) issues.push({ path: `${path}.payload`, message: `不允许的模板变量 ${match[1]}` })
    }
    return
  }
  issues.push({ path: `${path}.kind`, message: '不允许的动作类型' })
}

export function validateScreenScene(scene: ScreenScene): SceneIssue[] {
  const issues: SceneIssue[] = []
  if (scene.schemaVersion !== 1) issues.push({ path: 'schemaVersion', message: '只支持 schema 版本 1' })
  if (!SCREEN_MODES.includes(scene.mode)) issues.push({ path: 'mode', message: '显示模式无效' })
  numberIssue(scene.canvas.width, 'canvas.width', 1, 4096, issues)
  numberIssue(scene.canvas.height, 'canvas.height', 1, 4096, issues)
  numberIssue(scene.canvas.safeInset, 'canvas.safeInset', 0, Math.min(scene.canvas.width, scene.canvas.height) / 2, issues)
  for (const [index, layer] of scene.layers.entries()) {
    const path = `layers[${index}]`
    for (const [key, value] of [['x', layer.x], ['y', layer.y], ['width', layer.width], ['height', layer.height]] as const) numberIssue(value, `${path}.${key}`, 0, 4096, issues)
    numberIssue(layer.opacity, `${path}.opacity`, 0, 1, issues)
    if (layer.visibleWhen?.state && !SCREEN_STATES.includes(layer.visibleWhen.state)) issues.push({ path: `${path}.visibleWhen.state`, message: '状态无效' })
  }
  for (const [index, region] of scene.touchRegions.entries()) {
    const path = `touchRegions[${index}]`
    for (const [key, value] of [['x', region.x], ['y', region.y], ['width', region.width], ['height', region.height]] as const) numberIssue(value, `${path}.${key}`, 0, 4096, issues)
    validateAction(region.action, `${path}.action`, issues)
  }
  return issues
}
