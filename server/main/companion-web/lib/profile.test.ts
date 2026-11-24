import { describe, expect, it } from 'vitest'
import { dayLabel } from '../components/RoleRail'
import { daysTogether, maskPhone, planLine, toMillis } from './profile'

describe('profile helpers', () => {
  it('hides the middle of a phone number and leaves other names alone', () => {
    expect(maskPhone('13900000001')).toBe('139****0001')
    expect(maskPhone('+8613900000001')).toBe('139****0001')
    expect(maskPhone('admin')).toBe('admin')
  })

  it('reads timestamps whether they come as numbers or as strings of digits', () => {
    expect(toMillis(1795449600000)).toBe(1795449600000)
    expect(toMillis('1795449600000')).toBe(1795449600000)
    expect(toMillis('abc')).toBeNull()
    expect(toMillis(undefined)).toBeNull()
  })

  it('counts the day someone signed up as the first day together', () => {
    const now = new Date(2026, 10, 24, 9).getTime()
    expect(daysTogether(new Date(2026, 10, 24, 1).getTime(), now)).toBe(1)
    expect(daysTogether(new Date(2026, 10, 18, 23).getTime(), now)).toBe(7)
    expect(daysTogether(null, now)).toBeNull()
  })

  it('shows the plan with how many roles are used, or just the count when the limit is unknown', () => {
    expect(planLine({ label: '', plan: '内测版', maxRoles: 5, since: null }, 4)).toBe('内测版 · 角色 4/5')
    expect(planLine({ label: '', plan: '', maxRoles: null, since: null }, 2)).toBe('2 个角色')
  })

  it('names days as today, yesterday, a date, or a date with the year', () => {
    const now = new Date(2026, 10, 24, 10)
    expect(dayLabel('2026-11-24', now)).toBe('今天')
    expect(dayLabel('2026-11-23', now)).toBe('昨天')
    expect(dayLabel('2026-11-20', now)).toBe('11月20日')
    expect(dayLabel('2025-12-31', now)).toBe('2025年12月31日')
  })
})
