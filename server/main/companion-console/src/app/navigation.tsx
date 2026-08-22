import {
  ApiOutlined,
  AuditOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DesktopOutlined,
  IdcardOutlined,
  RobotOutlined,
  SettingOutlined,
  SoundOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import type { MenuDataItem } from '@ant-design/pro-components'
import type { ReactNode } from 'react'

export type ConsoleRouteKey =
  | 'dashboard'
  | 'playground'
  | 'devices'
  | 'deviceDetail'
  | 'profiles'
  | 'profileEditor'
  | 'memories'
  | 'subscription'
  | 'account'
  | 'voices'
  | 'models'
  | 'capabilities'
  | 'templates'
  | 'users'
  | 'adminDevices'
  | 'plans'
  | 'firmware'
  | 'audit'
  | 'systemSettings'

export interface ConsoleRouteMeta {
  key: ConsoleRouteKey
  path: string
  name: string
  icon?: ReactNode
  group?: '工作台' | 'AI 能力' | '平台管理'
  permission?: string
  showInMenu: boolean
  parentPath?: string
}

export const consoleRoutes: readonly ConsoleRouteMeta[] = [
  { key: 'dashboard', path: '/dashboard', name: '概览', icon: <DashboardOutlined />, group: '工作台', showInMenu: true },
  { key: 'playground', path: '/playground', name: '操练场', icon: <ThunderboltOutlined />, group: '工作台', permission: 'sys:role:normal', showInMenu: true },
  { key: 'devices', path: '/devices', name: '我的设备', icon: <DesktopOutlined />, group: '工作台', showInMenu: true },
  { key: 'deviceDetail', path: '/devices/:id', name: '设备详情', group: '工作台', showInMenu: false, parentPath: '/devices' },
  { key: 'profiles', path: '/profiles', name: '陪伴角色', icon: <RobotOutlined />, group: '工作台', showInMenu: true },
  { key: 'profileEditor', path: '/profiles/:id', name: '编辑陪伴角色', group: '工作台', showInMenu: false, parentPath: '/profiles' },
  { key: 'memories', path: '/memories', name: '记忆', icon: <AuditOutlined />, group: '工作台', showInMenu: true },
  { key: 'subscription', path: '/subscription', name: '订阅', icon: <IdcardOutlined />, group: '工作台', showInMenu: true },
  { key: 'account', path: '/account', name: '账号资料', showInMenu: false },
  { key: 'models', path: '/admin/models', name: '模型管理', icon: <ApiOutlined />, group: 'AI 能力', permission: 'sys:role:superAdmin', showInMenu: true },
  { key: 'capabilities', path: '/admin/capabilities', name: '能力中心', icon: <ThunderboltOutlined />, group: 'AI 能力', permission: 'sys:role:superAdmin', showInMenu: true },
  { key: 'voices', path: '/voices', name: '声音管理', icon: <SoundOutlined />, group: 'AI 能力', permission: 'sys:role:normal', showInMenu: true },
  { key: 'templates', path: '/admin/templates', name: '角色模板', icon: <RobotOutlined />, group: 'AI 能力', permission: 'sys:role:superAdmin', showInMenu: true },
  { key: 'users', path: '/admin/users', name: '用户管理', icon: <TeamOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true },
  { key: 'adminDevices', path: '/admin/devices', name: '设备运营', icon: <DesktopOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true },
  { key: 'plans', path: '/admin/plans', name: '套餐与订阅', icon: <IdcardOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true },
  { key: 'firmware', path: '/admin/firmware', name: '固件管理', icon: <DatabaseOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true },
  { key: 'audit', path: '/admin/audit', name: '审计日志', icon: <AuditOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true },
  { key: 'systemSettings', path: '/admin/system-settings', name: '系统设置', icon: <SettingOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true },
]

const groups: NonNullable<ConsoleRouteMeta['group']>[] = ['工作台', 'AI 能力', '平台管理']

function visible(route: ConsoleRouteMeta, hasPermission: (permission: string) => boolean) {
  return !route.permission || hasPermission(route.permission)
}

function routeItem(route: ConsoleRouteMeta): MenuDataItem {
  return {
    key: route.path,
    path: route.path,
    name: route.name,
    icon: route.icon,
    hideInMenu: !route.showInMenu,
    parentKeys: route.parentPath ? [route.parentPath] : undefined,
  }
}

export function consoleRouteByPath(path: string) {
  return consoleRoutes.find((route) => route.path === path)
}

export function consoleRouteByKey(key: ConsoleRouteKey) {
  const route = consoleRoutes.find((candidate) => candidate.key === key)
  if (!route) throw new Error(`Missing console route metadata: ${key}`)
  return route
}

export function routeTitle(key: ConsoleRouteKey) {
  return consoleRouteByKey(key).name
}

export function layoutRoutesForPermissions(hasPermission: (permission: string) => boolean): MenuDataItem[] {
  const allowed = consoleRoutes.filter((route) => visible(route, hasPermission))
  const grouped = groups.map((name) => ({
    name,
    children: allowed.filter((route) => route.group === name).map(routeItem),
  })).filter((group) => group.children.length > 0)
  return [...grouped, ...allowed.filter((route) => !route.group).map(routeItem)]
}

export function menuForPermissions(hasPermission: (permission: string) => boolean): MenuDataItem[] {
  return layoutRoutesForPermissions(hasPermission).map((item) => ({
    ...item,
    children: item.children?.filter((child) => !child.hideInMenu),
  })).filter((item) => !item.hideInMenu && (!item.children || item.children.length > 0))
}

function matchesPath(pattern: string, pathname: string) {
  const patternParts = pattern.split('/').filter(Boolean)
  const pathParts = pathname.split('/').filter(Boolean)
  return patternParts.length === pathParts.length
    && patternParts.every((part, index) => part.startsWith(':') || part === pathParts[index])
}

export function selectedMenuPath(pathname: string) {
  const cleanPath = pathname.split(/[?#]/, 1)[0]
  const route = consoleRoutes.find((candidate) => matchesPath(candidate.path, cleanPath))
  return route?.parentPath ?? route?.path ?? cleanPath
}
