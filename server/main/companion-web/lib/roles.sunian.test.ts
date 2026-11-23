import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { LIVE2D_MODELS, roleLook } from './roles'

describe('苏念的形象', () => {
  const look = roleLook('苏念')

  it('头像、全身像、眨眼和张嘴的图片都在 public/roles 里', () => {
    const files = [look.avatar, look.body, look.art?.base, look.art?.blink, look.art?.talk]
    for (const file of files) {
      expect(file).toMatch(/^\/roles\/sunian[-a-z]*\.webp$/)
      expect(existsSync(join(__dirname, '..', 'public', file as string))).toBe(true)
    }
  })

  it('有介绍和三句开场话，Live2D 指向已登记的模型', () => {
    expect(look.tagline).toBeTruthy()
    expect(look.starters).toHaveLength(3)
    expect(LIVE2D_MODELS.map((model) => model.key)).toContain(look.live2d)
  })
})
