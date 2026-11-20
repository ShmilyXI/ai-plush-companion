import {
  ApiOutlined,
  AuditOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DesktopOutlined,
  IdcardOutlined,
  LineChartOutlined,
  RobotOutlined,
  SafetyOutlined,
  SettingOutlined,
  SoundOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import type { MenuDataItem } from '@ant-design/pro-components'
import type { ReactNode } from 'react'

export type ConsoleRouteKey =
  | 'dashboard'
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
  | 'inviteCodes'
  | 'usage'
  | 'safetyEvents'
  | 'analytics'
  | 'firmware'
  | 'audit'
  | 'systemSettings'
  | 'systemParams'
  | 'screenComposer'

/**
 * 暂时不用、默认从菜单和路由里隐藏的页面分组。
 * 硬件冻结期间后台只保留网页对话需要的页面；构建时设置 VITE_CONSOLE_FEATURES
 * （逗号分隔，或写 all）可以把对应的页面放回来。
 */
export type ConsoleFeature = 'hardware' | 'workbench' | 'billing' | 'capabilities'

export const allConsoleFeatures: readonly ConsoleFeature[] = ['hardware', 'workbench', 'billing', 'capabilities']

export function parseConsoleFeatures(raw: unknown): ReadonlySet<ConsoleFeature> {
  if (typeof raw !== 'string') return new Set()
  const names = raw.split(',').map((name) => name.trim().toLowerCase()).filter(Boolean)
  if (names.includes('all')) return new Set(allConsoleFeatures)
  return new Set(allConsoleFeatures.filter((feature) => names.includes(feature)))
}

export function enabledConsoleFeatures(): ReadonlySet<ConsoleFeature> {
  return parseConsoleFeatures(import.meta.env.VITE_CONSOLE_FEATURES)
}

/** 一个菜单项下面并排放的几个页面，页面之间用顶部的切换条跳转。 */
export type ConsoleSectionKey = 'modelsAndVoices' | 'users' | 'data' | 'settings'

const sections: Record<ConsoleSectionKey, { name: string; icon: ReactNode }> = {
  modelsAndVoices: { name: '模型与声音', icon: <ApiOutlined /> },
  users: { name: '用户', icon: <TeamOutlined /> },
  data: { name: '数据', icon: <LineChartOutlined /> },
  settings: { name: '设置', icon: <SettingOutlined /> },
}

export interface ConsoleRouteMeta {
  key: ConsoleRouteKey
  path: string
  name: string
  icon?: ReactNode
  group?: '工作台' | 'AI 能力' | '平台管理'
  permission?: string
  showInMenu: boolean
  parentPath?: string
  /** 属于哪个默认隐藏的分组；不写表示始终可用。 */
  feature?: ConsoleFeature
  /** 和哪些页面合并成一个菜单项；sectionLabel 是切换条上的名字。 */
  section?: ConsoleSectionKey
  sectionLabel?: string
}

// 数组顺序就是菜单顺序，同一个 section 里的顺序就是切换条的顺序。
export const consoleRoutes: readonly ConsoleRouteMeta[] = [
  { key: 'dashboard', path: '/dashboard', name: '概览', icon: <DashboardOutlined />, group: '工作台', showInMenu: true },
  { key: 'devices', path: '/devices', name: '我的设备', icon: <DesktopOutlined />, group: '工作台', showInMenu: true, feature: 'hardware' },
  { key: 'deviceDetail', path: '/devices/:id', name: '设备详情', group: '工作台', showInMenu: false, parentPath: '/devices', feature: 'hardware' },
  { key: 'profiles', path: '/profiles', name: '陪伴角色', icon: <RobotOutlined />, group: '工作台', showInMenu: true, feature: 'workbench' },
  { key: 'profileEditor', path: '/profiles/:id', name: '编辑陪伴角色', group: '工作台', showInMenu: false, parentPath: '/profiles', feature: 'workbench' },
  { key: 'memories', path: '/memories', name: '记忆', icon: <AuditOutlined />, group: '工作台', showInMenu: true, feature: 'workbench' },
  { key: 'subscription', path: '/subscription', name: '订阅', icon: <IdcardOutlined />, group: '工作台', showInMenu: true, feature: 'billing' },
  { key: 'screenComposer', path: '/screen-composer', name: '屏幕编排', icon: <DesktopOutlined />, group: '工作台', showInMenu: true, feature: 'hardware' },
  { key: 'account', path: '/account', name: '账号资料', showInMenu: false },
  { key: 'templates', path: '/admin/templates', name: '预设角色', icon: <RobotOutlined />, group: 'AI 能力', permission: 'sys:role:superAdmin', showInMenu: true },
  { key: 'models', path: '/admin/models', name: '模型管理', icon: <ApiOutlined />, group: 'AI 能力', permission: 'sys:role:superAdmin', showInMenu: true, section: 'modelsAndVoices', sectionLabel: '模型' },
  { key: 'voices', path: '/voices', name: '声音管理', icon: <SoundOutlined />, group: 'AI 能力', permission: 'sys:role:normal', showInMenu: true, section: 'modelsAndVoices', sectionLabel: '声音' },
  { key: 'capabilities', path: '/admin/capabilities', name: '能力中心', icon: <ThunderboltOutlined />, group: 'AI 能力', permission: 'sys:role:superAdmin', showInMenu: true, feature: 'capabilities' },
  { key: 'users', path: '/admin/users', name: '用户管理', icon: <TeamOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true, section: 'users', sectionLabel: '用户' },
  { key: 'inviteCodes', path: '/admin/invite-codes', name: '邀请码', icon: <IdcardOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true, section: 'users', sectionLabel: '邀请码' },
  { key: 'analytics', path: '/admin/analytics', name: '留存与体验', icon: <LineChartOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true, section: 'data', sectionLabel: '留存与体验' },
  { key: 'usage', path: '/admin/usage', name: '对话用量', icon: <LineChartOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true, section: 'data', sectionLabel: '对话用量' },
  { key: 'safetyEvents', path: '/admin/safety-events', name: '安全事件', icon: <SafetyOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true },
  { key: 'adminDevices', path: '/admin/devices', name: '设备运营', icon: <DesktopOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true, feature: 'hardware' },
  { key: 'firmware', path: '/admin/firmware', name: '固件管理', icon: <DatabaseOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true, feature: 'hardware' },
  { key: 'plans', path: '/admin/plans', name: '套餐与订阅', icon: <IdcardOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true, feature: 'billing' },
  { key: 'systemSettings', path: '/admin/system-settings', name: '系统设置', icon: <SettingOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true, section: 'settings', sectionLabel: '系统设置' },
  { key: 'systemParams', path: '/admin/system-params', name: '参数管理', icon: <SettingOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true, section: 'settings', sectionLabel: '参数' },
  { key: 'audit', path: '/admin/audit', name: '审计日志', icon: <AuditOutlined />, group: '平台管理', permission: 'sys:role:superAdmin', showInMenu: true, section: 'settings', sectionLabel: '审计日志' },
]

const groups: NonNullable<ConsoleRouteMeta['group']>[] = ['工作台', 'AI 能力', '平台管理']

type PermissionCheck = (permission: string) => boolean

const allowEverything: PermissionCheck = () => true

/** 当前构建里可以打开的页面，不考虑登录人的权限。 */
export function availableConsoleRoutes(features: ReadonlySet<ConsoleFeature> = enabledConsoleFeatures()) {
  return consoleRoutes.filter((route) => !route.feature || features.has(route.feature))
}

function visibleRoutes(hasPermission: PermissionCheck, features: ReadonlySet<ConsoleFeature>) {
  return availableConsoleRoutes(features).filter((route) => !route.permission || hasPermission(route.permission))
}

function sectionMembers(section: ConsoleSectionKey, visible: readonly ConsoleRouteMeta[]) {
  return visible.filter((route) => route.section === section)
}

function routeItem(route: ConsoleRouteMeta, visible: readonly ConsoleRouteMeta[]): MenuDataItem {
  const members = route.section ? sectionMembers(route.section, visible) : []
  // 合并的菜单项挂在这一组里当前登录人能看到的第一个页面上，其余页面不进菜单。
  // 只看得到一个页面时不算合并，沿用页面自己的名字。
  const merged = members.length > 1
  const lead = merged ? members[0] : route
  const isLead = lead.key === route.key
  const section = merged && route.section ? sections[route.section] : undefined
  return {
    key: route.path,
    path: route.path,
    name: isLead && section ? section.name : route.name,
    icon: isLead && section ? section.icon : route.icon,
    hideInMenu: !route.showInMenu || !isLead,
    parentKeys: !isLead ? [lead.path] : route.parentPath ? [route.parentPath] : undefined,
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

export function layoutRoutesForPermissions(
  hasPermission: PermissionCheck,
  features: ReadonlySet<ConsoleFeature> = enabledConsoleFeatures(),
): MenuDataItem[] {
  const visible = visibleRoutes(hasPermission, features)
  // 只剩网页对话用的几项时不分组，平铺更清楚；放回隐藏的页面后菜单变长，再按业务分组。
  if (features.size === 0) return visible.map((route) => routeItem(route, visible))
  const grouped = groups.map((name) => ({
    name,
    children: visible.filter((route) => route.group === name).map((route) => routeItem(route, visible)),
  })).filter((group) => group.children.length > 0)
  return [...grouped, ...visible.filter((route) => !route.group).map((route) => routeItem(route, visible))]
}

export function menuForPermissions(
  hasPermission: PermissionCheck,
  features: ReadonlySet<ConsoleFeature> = enabledConsoleFeatures(),
): MenuDataItem[] {
  return layoutRoutesForPermissions(hasPermission, features).map((item) => ({
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

function routeForPathname(pathname: string) {
  const cleanPath = pathname.split(/[?#]/, 1)[0]
  return { cleanPath, route: consoleRoutes.find((candidate) => matchesPath(candidate.path, cleanPath)) }
}

export function selectedMenuPath(
  pathname: string,
  hasPermission: PermissionCheck = allowEverything,
  features: ReadonlySet<ConsoleFeature> = enabledConsoleFeatures(),
) {
  const { cleanPath, route } = routeForPathname(pathname)
  if (route?.section) {
    const members = sectionMembers(route.section, visibleRoutes(hasPermission, features))
    if (members.length > 1) return members[0].path
  }
  return route?.parentPath ?? route?.path ?? cleanPath
}

export interface ConsoleSectionSwitch {
  name: string
  activePath: string
  pages: { path: string; label: string }[]
}

/** 当前页面所在的菜单项如果合并了几个页面，返回它们的切换条；否则返回 null。 */
export function sectionSwitchFor(
  pathname: string,
  hasPermission: PermissionCheck,
  features: ReadonlySet<ConsoleFeature> = enabledConsoleFeatures(),
): ConsoleSectionSwitch | null {
  const { route } = routeForPathname(pathname)
  if (!route?.section) return null
  const members = sectionMembers(route.section, visibleRoutes(hasPermission, features))
  if (members.length < 2 || !members.some((member) => member.key === route.key)) return null
  return {
    name: sections[route.section].name,
    activePath: route.path,
    pages: members.map((member) => ({ path: member.path, label: member.sectionLabel ?? member.name })),
  }
}
