import { describe, expect, it } from 'vitest'

import {
  allConsoleFeatures,
  availableConsoleRoutes,
  consoleRouteByPath,
  consoleRoutes,
  layoutRoutesForPermissions,
  menuForPermissions,
  parseConsoleFeatures,
  sectionSwitchFor,
  selectedMenuPath,
  type ConsoleFeature,
} from './navigation'

const normal = (permission: string) => permission === 'sys:role:normal'
const admin = (permission: string) => ['sys:role:normal', 'sys:role:superAdmin'].includes(permission)
const none: ReadonlySet<ConsoleFeature> = new Set()
const everything: ReadonlySet<ConsoleFeature> = new Set(allConsoleFeatures)

function names(menu: ReturnType<typeof menuForPermissions>) {
  return menu.flatMap((item) => item.children ? item.children.map((child) => child.name) : [item.name])
}

describe('default console navigation', () => {
  it('gives an administrator seven flat menu items', () => {
    const menu = menuForPermissions(admin, none)
    expect(names(menu)).toEqual(['概览', '网页端', '预设角色', '模型与声音', '用户', '数据', '安全事件', '设置'])
    expect(menu.every((item) => !item.children)).toBe(true)
  })

  it('hides hardware, the old user workbench, billing and the capability center', () => {
    const paths = availableConsoleRoutes(none).map((route) => route.path)
    for (const hidden of ['/devices', '/devices/:id', '/screen-composer', '/admin/devices', '/admin/firmware',
      '/profiles', '/profiles/:id', '/memories', '/subscription', '/admin/plans', '/admin/capabilities']) {
      expect(paths).not.toContain(hidden)
    }
    expect(paths).toContain('/account')
  })

  it('leaves an ordinary user the overview and their own voices', () => {
    expect(names(menuForPermissions(normal, none))).toEqual(['概览', '网页端', '声音管理'])
    expect(JSON.stringify(layoutRoutesForPermissions(normal, none))).not.toContain('/admin/')
  })

  it('reads the hidden page groups from the build setting', () => {
    expect(parseConsoleFeatures(undefined).size).toBe(0)
    expect(parseConsoleFeatures('').size).toBe(0)
    expect([...parseConsoleFeatures(' Hardware, billing ,unknown')]).toEqual(['hardware', 'billing'])
    expect([...parseConsoleFeatures('all')]).toEqual([...allConsoleFeatures])
  })
})

describe('pages merged under one menu item', () => {
  it('keeps every merged page reachable and selects the shared menu item', () => {
    const routes = JSON.stringify(layoutRoutesForPermissions(admin, none))
    for (const path of ['/admin/models', '/voices', '/admin/users', '/admin/invite-codes', '/admin/analytics',
      '/admin/usage', '/admin/system-settings', '/admin/system-params', '/admin/audit']) {
      expect(routes).toContain(path)
    }
    expect(selectedMenuPath('/voices?tab=clone', admin, none)).toBe('/admin/models')
    expect(selectedMenuPath('/admin/invite-codes', admin, none)).toBe('/admin/users')
    expect(selectedMenuPath('/admin/usage', admin, none)).toBe('/admin/analytics')
    expect(selectedMenuPath('/admin/audit', admin, none)).toBe('/admin/system-settings')
  })

  it('lists the sibling pages for the switch above the page', () => {
    expect(sectionSwitchFor('/admin/system-params', admin, none)).toEqual({
      name: '设置',
      activePath: '/admin/system-params',
      pages: [
        { path: '/admin/system-settings', label: '系统设置' },
        { path: '/admin/system-params', label: '参数' },
        { path: '/admin/audit', label: '审计日志' },
      ],
    })
    expect(sectionSwitchFor('/voices', admin, none)?.pages.map((page) => page.label)).toEqual(['模型', '声音'])
    expect(sectionSwitchFor('/dashboard', admin, none)).toBeNull()
    expect(sectionSwitchFor('/admin/safety-events', admin, none)).toBeNull()
  })

  it('does not merge when the signed-in user can open only one of the pages', () => {
    expect(sectionSwitchFor('/voices', normal, none)).toBeNull()
    expect(selectedMenuPath('/voices?tab=clone', normal, none)).toBe('/voices')
    const voices = layoutRoutesForPermissions(normal, none).find((item) => item.path === '/voices')
    expect(voices).toMatchObject({ name: '声音管理', hideInMenu: false })
  })
})

describe('console navigation with every hidden page group switched back on', () => {
  it('shows workbench and voice clone access to a normal user', () => {
    const labels = JSON.stringify(menuForPermissions(normal, everything))
    expect(labels).toContain('工作台')
    expect(labels).toContain('声音管理')
    expect(labels).not.toContain('操练场')
    expect(labels).not.toContain('平台管理')
    expect(labels).not.toContain('模型管理')
  })

  it('does not expose the retired playground as an active route', () => {
    expect(consoleRouteByPath('/playground')).toBeUndefined()
  })

  it('groups the administrator menu by business domain', () => {
    const labels = JSON.stringify(menuForPermissions(admin, everything))
    expect(labels).toContain('AI 能力')
    expect(labels).toContain('能力中心')
    expect(labels).toContain('平台管理')
    expect(labels).toContain('设备运营')
    expect(labels).not.toContain('资源管理')
  })

  it('keeps detail and tab routes attached to their parent menu item', () => {
    expect(selectedMenuPath('/devices/device-a', normal, everything)).toBe('/devices')
    expect(selectedMenuPath('/profiles/profile-a', normal, everything)).toBe('/profiles')
    expect(selectedMenuPath('/voices?tab=clone', normal, everything)).toBe('/voices')
  })

  it('uses the same metadata for menu visibility, route permissions, and titles', () => {
    expect(consoleRouteByPath('/admin/models')).toMatchObject({
      name: '模型管理',
      group: 'AI 能力',
      permission: 'sys:role:superAdmin',
      showInMenu: true,
    })
    expect(consoleRouteByPath('/devices/:id')).toMatchObject({
      name: '设备详情',
      parentPath: '/devices',
      showInMenu: false,
    })
    expect(JSON.stringify(layoutRoutesForPermissions(normal, everything))).not.toContain('/admin/models')
    expect(JSON.stringify(layoutRoutesForPermissions(admin, everything))).toContain('/admin/models')
    expect(consoleRouteByPath('/admin/capabilities')).toMatchObject({
      name: '能力中心',
      group: 'AI 能力',
      permission: 'sys:role:superAdmin',
      showInMenu: true,
    })
  })

  it('keeps agent ownership separate from administrator capability and model catalogs', () => {
    const normalMenu = JSON.stringify(menuForPermissions(normal, everything))
    const adminMenu = JSON.stringify(menuForPermissions(admin, everything))
    expect(normalMenu).toContain('/profiles')
    expect(normalMenu).toContain('/devices')
    expect(normalMenu).toContain('/memories')
    expect(normalMenu).not.toContain('/admin/capabilities')
    expect(normalMenu).not.toContain('/admin/models')
    expect(adminMenu).toContain('/admin/capabilities')
    expect(adminMenu).toContain('/admin/models')
    expect(adminMenu).not.toContain('资源管理')
  })

  it('has one canonical Skill ownership path and keeps device details read-only', () => {
    const paths = consoleRoutes.map((route) => route.path)
    expect(paths.filter((path) => path.includes('skill')).length).toBe(0)
    expect(paths).toContain('/profiles/:id')
    expect(paths).toContain('/devices/:id')
    expect(selectedMenuPath('/devices/device-a', normal, everything)).toBe('/devices')
  })

  it('keeps the real detail route in layout metadata for accurate breadcrumbs', () => {
    const routes = JSON.stringify(layoutRoutesForPermissions(normal, everything))
    expect(routes).toContain('/devices')
    expect(routes).toContain('/devices/:id')
    expect(routes).toContain('设备详情')
  })
})
