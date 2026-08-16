import { describe, expect, it } from 'vitest'

import {
  consoleRouteByPath,
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
    expect(labels).not.toContain('平台管理')
    expect(labels).not.toContain('模型管理')
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

  it('keeps the real detail route in layout metadata for accurate breadcrumbs', () => {
    const routes = JSON.stringify(layoutRoutesForPermissions(normal))
    expect(routes).toContain('/devices')
    expect(routes).toContain('/devices/:id')
    expect(routes).toContain('设备详情')
  })
})
