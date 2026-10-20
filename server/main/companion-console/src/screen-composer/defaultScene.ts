import type { ScreenScene } from './sceneTypes'

/**
 * Scene a fresh browser starts with. It carries one expression layer so the canvas is never blank:
 * scenes live in each browser's localStorage, so a new machine would otherwise show nothing.
 */
export function createDefaultScene(): ScreenScene {
  return {
    schemaVersion: 1, sceneId: 'esp-vocat-default', version: 1, mode: 'emotion_chat',
    canvas: { width: 360, height: 360, shape: 'circle', safeInset: 24 },
    layers: [{ id: 'layer-default-expression', type: 'animation', resource: 'assets/neutral.eaf', x: 24, y: 24, width: 312, height: 312, opacity: 1 }],
    touchRegions: [], resources: {},
  }
}
