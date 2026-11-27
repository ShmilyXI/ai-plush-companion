import { CAST } from '@mowa-bot/engine'
import { afterEach, describe, expect, it } from 'vitest'
import { characterKind } from './characterStyle'
import { MOWA_TEMPLATES, customLook, hasPresetLook, roleLook, setCustomLook } from './roles'

// 两个 Mowa Bot 角色模板（服务端 CompanionPresetRoles 的 糯糯、哔哔）：从模板建出来的角色默认就是对应的 Mowa Bot。
describe('Mowa Bot role templates', () => {
  afterEach(() => { setCustomLook('糯糯', null); setCustomLook('哔哔', null) })

  it('draws 糯糯 and 哔哔 as their Mowa Bot characters, with their own tagline and openers', () => {
    expect(Object.keys(MOWA_TEMPLATES)).toEqual(['糯糯', '哔哔'])
    for (const [name, template] of Object.entries(MOWA_TEMPLATES)) {
      expect(CAST.some((member) => member.id === template.look.mowa), `${name} uses a real Mowa Bot character`).toBe(true)
      expect(characterKind(name)).toBe('mowa')
      expect(hasPresetLook(name)).toBe(false)
      const look = roleLook(name)
      expect(look.tagline).toBe(template.tagline)
      expect(look.starters).toEqual(template.starters)
    }
    expect(customLook('糯糯')?.mowa).toBe('mochi')
    expect(customLook('哔哔')?.mowa).toBe('beep')
  })

  it('lets the user pick another look, and falls back to the Mowa Bot default when the choice is cleared', () => {
    setCustomLook('哔哔', { kind: 'rabbit', color: 'peach' })
    expect(characterKind('哔哔')).toBe('2d')
    expect(roleLook('哔哔').tagline).toBe(MOWA_TEMPLATES['哔哔'].tagline)
    setCustomLook('哔哔', null)
    expect(characterKind('哔哔')).toBe('mowa')
  })
})
