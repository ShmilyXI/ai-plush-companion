import { describe, expect, it } from 'vitest'
import { DEFAULT_APPEARANCE, engineOptions, sanitizeAppearance } from './appearance'

describe('sanitizeAppearance', () => {
  it('falls back to defaults for junk and keeps ball as the default character', () => {
    expect(sanitizeAppearance(null)).toEqual(DEFAULT_APPEARANCE)
    expect(sanitizeAppearance('x').character).toBe('ball')
  })

  it('keeps valid fields and clamps or rejects invalid ones', () => {
    const result = sanitizeAppearance({ character: 'nimbo', shape: 'hexagon', eyeScale: 9, color: 'red', gazeX: -5, sketch: true })
    expect(result).toMatchObject({ character: 'nimbo', shape: 'blob', eyeScale: 2, color: DEFAULT_APPEARANCE.color, gazeX: -1, sketch: true })
  })
})

describe('engineOptions', () => {
  it('sends shape only for the ball and colors only when customised', () => {
    expect(engineOptions({ ...DEFAULT_APPEARANCE, shape: 'gem' })).toEqual({ eyeScale: 1, lite: false, idle: false, shape: 'gem' })
    const themed = engineOptions({ ...DEFAULT_APPEARANCE, character: 'twinkle', customColor: true, color: '#ff0000', eyeColor: '#00ff00' })
    expect(themed).toEqual({ eyeScale: 1, lite: false, idle: false, color: '#ff0000', eyeColor: '#00ff00' })
  })
})
