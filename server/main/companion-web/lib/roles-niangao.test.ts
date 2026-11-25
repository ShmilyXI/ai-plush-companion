import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { LIVE2D_MODELS, PREVIEW_NAME, currentOutfit, roleLook, roleOutfits, setOutfit, setPreviewOutfit, type RoleLook } from './roles'

const imagesOf = (look: RoleLook) => [look.avatar, look.body, look.art?.base, look.art?.blink, look.art?.talk]

// 年糕的形象文件和登记要对得上：少一张图，选角色和通话时就会出现裂图。
describe('年糕 preset look', () => {
  afterEach(() => { setOutfit('年糕', null); setPreviewOutfit(null) })

  it('points at images that exist in public/, for every outfit', () => {
    const outfits = roleOutfits('年糕')
    expect(outfits.map((item) => item.key)).toEqual(['host', 'lady', 'silver', 'chibi', 'classic'])
    for (const outfit of outfits) {
      setOutfit('年糕', outfit.key)
      for (const file of imagesOf(roleLook('年糕'))) {
        expect(file, `${outfit.key} image path`).toMatch(/^\/roles\/niangao.*\.webp$/)
        expect(existsSync(join(__dirname, '..', 'public', file as string)), String(file)).toBe(true)
      }
    }
  })

  it('has a tagline, starters, a palette and a known Live2D stand-in', () => {
    const look = roleLook('年糕')
    expect(look.tagline).toBeTruthy()
    expect(look.starters).toHaveLength(3)
    expect(look.aura).toHaveLength(3)
    expect(LIVE2D_MODELS.map((item) => item.key)).toContain(look.live2d)
  })

  it('wears the host outfit by default and switches outfits without losing the persona', () => {
    expect(currentOutfit('年糕')).toBe('host')
    expect(roleLook('年糕').body).toBe('/roles/niangao-host.webp')
    setOutfit('年糕', 'chibi')
    const look = roleLook('年糕')
    expect(look.body).toBe('/roles/niangao-chibi.webp')
    expect(look.tagline).toBe('自称舒适之神的懒猫')
    // 不认识的装扮当作没选，回到默认那套。
    setOutfit('年糕', 'nope')
    expect(roleLook('年糕').body).toBe('/roles/niangao-host.webp')
  })

  it('previews an outfit without changing the one in use, and other roles have none', () => {
    setPreviewOutfit('年糕', 'lady')
    expect(roleLook(PREVIEW_NAME).body).toBe('/roles/niangao-lady.webp')
    expect(roleLook('年糕').body).toBe('/roles/niangao-host.webp')
    expect(roleOutfits('苏念')).toEqual([])
    expect(currentOutfit('苏念')).toBeUndefined()
  })
})
