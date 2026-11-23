import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { LIVE2D_MODELS, roleLook } from './roles'

// 年糕的形象文件和登记要对得上：少一张图，选角色和通话时就会出现裂图。
describe('年糕 preset look', () => {
  const look = roleLook('年糕')
  const files = [look.avatar, look.body, look.art?.base, look.art?.blink, look.art?.talk]

  it('points at images that exist in public/', () => {
    for (const file of files) {
      expect(file, 'image path').toMatch(/^\/roles\/niangao.*\.webp$/)
      expect(existsSync(join(__dirname, '..', 'public', file as string)), String(file)).toBe(true)
    }
  })

  it('has a tagline, starters, a palette and a known Live2D stand-in', () => {
    expect(look.tagline).toBeTruthy()
    expect(look.starters).toHaveLength(3)
    expect(look.aura).toHaveLength(3)
    expect(LIVE2D_MODELS.map((item) => item.key)).toContain(look.live2d)
  })
})
