import { describe, expect, it } from 'vitest'
import { createDefaultScene } from './defaultScene'
import { validateScreenScene } from './sceneValidation'

describe('createDefaultScene', () => {
  it('starts with a visible neutral expression and passes validation', () => {
    const scene = createDefaultScene()
    expect(scene.layers).toHaveLength(1)
    expect(scene.layers[0]).toMatchObject({ type: 'animation', resource: 'assets/neutral.eaf' })
    expect(validateScreenScene(scene)).toEqual([])
  })

  it('returns a fresh object each time', () => {
    expect(createDefaultScene()).not.toBe(createDefaultScene())
  })
})
