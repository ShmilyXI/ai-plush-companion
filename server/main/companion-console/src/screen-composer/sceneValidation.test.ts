import { describe, expect, it } from 'vitest'
import { validateScreenScene } from './sceneValidation'
import type { ScreenScene } from './sceneTypes'

const scene: ScreenScene = {
  schemaVersion: 1, sceneId: 'demo', version: 1, mode: 'emotion_chat',
  canvas: { width: 360, height: 360, shape: 'circle', safeInset: 24 },
  layers: [{ id: 'face', type: 'animation', resource: 'happy.eaf', x: 0, y: 0, width: 360, height: 360, opacity: 1, visibleWhen: { state: 'speaking' } }],
  touchRegions: [{ id: 'volume', shape: 'rect', x: 24, y: 24, width: 96, height: 72, action: { kind: 'device_tool', name: 'self.audio_speaker.set_volume', arguments: { volume: 80 } } }],
  resources: {},
}

describe('validateScreenScene', () => {
  it('accepts a valid circle emotion scene', () => expect(validateScreenScene(scene)).toEqual([]))
  it('rejects executable or unsafe mqtt content', () => {
    const issues = validateScreenScene({ ...scene, touchRegions: [{ ...scene.touchRegions[0], action: { kind: 'mqtt_template', topic: 'http://evil', qos: 2 as never, payload: { script: '${__proto__}' } } }] })
    expect(issues.map((issue) => issue.message)).toEqual(expect.arrayContaining(['MQTT topic 必须以 zixuan/ 开头', 'QoS 只允许 0 或 1', '不允许的模板变量 __proto__']))
  })
  it('rejects unknown states and oversized coordinates', () => {
    const issues = validateScreenScene({ ...scene, layers: [{ ...scene.layers[0], x: 5000, visibleWhen: { state: 'unknown' as never } }] })
    expect(issues).toHaveLength(2)
  })
})
