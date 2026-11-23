import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { LIVE2D_MODELS, roleLook } from './roles'

describe('江屿 preset', () => {
  it('points at images that exist and uses a real Live2D model', () => {
    const look = roleLook('江屿')
    const files = [look.avatar, look.body, look.art?.base, look.art?.blink, look.art?.talk]
    for (const file of files) {
      expect(file).toBeTruthy()
      expect(existsSync(join(process.cwd(), 'public', file!))).toBe(true)
    }
    expect(LIVE2D_MODELS.map((model) => model.key)).toContain(look.live2d)
    expect(look.tagline).toBeTruthy()
    expect(look.starters).toHaveLength(3)
  })
})
