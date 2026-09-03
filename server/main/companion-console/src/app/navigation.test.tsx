import { describe, expect, it } from 'vitest'

import {
  consoleRouteByPath,
  consoleRoutes,
  layoutRoutesForPermissions,
  menuForPermissions,
  selectedMenuPath,
} from './navigation'

const normal = (permission: string) => permission === 'sys:role:normal'
const admin = (permission: string) => ['sys:role:normal', 'sys:role:superAdmin'].includes(permission)

describe('console navigation', () => {
  it('shows workbench and voice clone access to a normal user', () => {
    const labels = JSON.stringify(menuForPermissions(normal))
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
    const labels = JSON.stringify(menuForPermissions(admin))
    expect(labels).toContain('AI 能力')
    expect(labels).toContain('能力中心')
    expect(labels).toContain('平台管理')
    expect(labels).toContain('设备运营')
    expect(labels).not.toContain('资源管理')
  })

  it('keeps detail and tab routes attached to their parent menu item', () => {
    expect(selectedMenuPath('/devices/device-a')).toBe('/devices')
    expect(selectedMenuPath('/profiles/profile-a')).toBe('/profiles')
    expect(selectedMenuPath('/voices?tab=clone')).toBe('/voices')
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
    expect(JSON.stringify(layoutRoutesForPermissions(normal))).not.toContain('/admin/models')
    expect(JSON.stringify(layoutRoutesForPermissions(admin))).toContain('/admin/models')
    expect(consoleRouteByPath('/admin/capabilities')).toMatchObject({
      name: '能力中心',
      group: 'AI 能力',
      permission: 'sys:role:superAdmin',
      showInMenu: true,
    })
  })

  it('keeps agent ownership separate from administrator capability and model catalogs', () => {
    const normalMenu = JSON.stringify(menuForPermissions(normal))
    const adminMenu = JSON.stringify(menuForPermissions(admin))
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
    expect(selectedMenuPath('/devices/device-a')).toBe('/devices')
  })

  it('keeps the real detail route in layout metadata for accurate breadcrumbs', () => {
    const routes = JSON.stringify(layoutRoutesForPermissions(normal))
    expect(routes).toContain('/devices')
    expect(routes).toContain('/devices/:id')
    expect(routes).toContain('设备详情')
  })
})
