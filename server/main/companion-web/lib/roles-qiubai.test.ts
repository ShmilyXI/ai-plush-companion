import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { roleLook } from './roles'

describe('秋白 preset look', () => {
  it('points at her own art, which exists in public/', () => {
    const look = roleLook('秋白')
    const files = [look.avatar, look.body, look.art?.base, look.art?.blink, look.art?.talk]
    for (const file of files) {
      expect(file).toMatch(/^\/roles\/qiubai/)
      expect(existsSync(join(process.cwd(), 'public', file as string))).toBe(true)
    }
    expect(look.tagline).toBeTruthy()
    expect(look.starters).toHaveLength(3)
    expect(look.plush).toBeUndefined()
  })
})
