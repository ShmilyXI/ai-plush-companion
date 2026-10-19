export const SCREEN_MODES = ['static_image', 'chat', 'emotion_chat', 'text_chat', 'custom'] as const
export type ScreenMode = typeof SCREEN_MODES[number]

export const SCREEN_STATES = ['idle', 'listening', 'thinking', 'speaking', 'error'] as const
export type ScreenState = typeof SCREEN_STATES[number]

export interface ScreenCanvas {
  width: number
  height: number
  shape: 'rect' | 'circle'
  safeInset: number
}

export interface SceneLayer {
  id: string
  type: 'image' | 'text' | 'animation'
  resource?: string
  text?: string
  x: number
  y: number
  width: number
  height: number
  opacity: number
  visibleWhen?: { state?: ScreenState; mode?: ScreenMode }
}

export interface DeviceToolAction {
  kind: 'device_tool'
  name: string
  arguments: Record<string, unknown>
}

export interface MqttTemplateAction {
  kind: 'mqtt_template'
  topic: string
  qos: 0 | 1
  payload: Record<string, unknown>
}

export type TouchAction = DeviceToolAction | MqttTemplateAction

export interface TouchRegion {
  id: string
  shape: 'rect' | 'circle'
  x: number
  y: number
  width: number
  height: number
  action: TouchAction
}

export interface ScreenScene {
  schemaVersion: 1
  sceneId: string
  version: number
  mode: ScreenMode
  canvas: ScreenCanvas
  layers: SceneLayer[]
  touchRegions: TouchRegion[]
  resources: { manifestSha256?: string }
}

export interface ScreenDeviceCapabilities {
  width: number
  height: number
  shape: 'rect' | 'circle'
  touch: { width: number; height: number; longPressMs: number }
  modes: ScreenMode[]
  resourceTypes: Array<'image' | 'text' | 'eaf'>
  maxPackageBytes: number
}
