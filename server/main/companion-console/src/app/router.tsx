/* eslint-disable react-refresh/only-export-components */
import { lazy, Suspense, type ReactNode } from 'react'
import { createBrowserRouter, Navigate, Outlet, useLocation, useNavigate, useRouteError } from 'react-router-dom'

import { useAuthStore } from '../auth/authStore'
import { consoleRouteByKey, consoleRoutes, type ConsoleRouteKey } from './navigation'

const LoginPage = lazy(() => import('../pages/LoginPage').then((module) => ({ default: module.LoginPage })))
const RegisterPage = lazy(() => import('../pages/RegisterPage').then((module) => ({ default: module.RegisterPage })))
const AppShell = lazy(() => import('./AppShell').then((module) => ({ default: module.AppShell })))
const DashboardPage = lazy(() => import('../pages/DashboardPage').then((module) => ({ default: module.DashboardPage })))
const DeviceListPage = lazy(() => import('../pages/devices/DeviceListPage').then((module) => ({ default: module.DeviceListPage })))
const DeviceDetailPage = lazy(() => import('../pages/devices/DeviceDetailPage').then((module) => ({ default: module.DeviceDetailPage })))
const ProfileListPage = lazy(() => import('../pages/profiles/ProfileListPage').then((module) => ({ default: module.ProfileListPage })))
const ProfileEditorPage = lazy(() => import('../pages/profiles/ProfileEditorPage').then((module) => ({ default: module.ProfileEditorPage })))
const MemoryPage = lazy(() => import('../pages/memories/MemoryPage').then((module) => ({ default: module.MemoryPage })))
const ModelManagementPage = lazy(() => import('../pages/models/ModelManagementPage').then((module) => ({ default: module.ModelManagementPage })))
const CapabilityManagementPage = lazy(() => import('../pages/admin/CapabilityManagementPage').then((module) => ({ default: module.CapabilityManagementPage })))
const VoiceManagementPage = lazy(() => import('../pages/voices/VoiceManagementPage').then((module) => ({ default: module.VoiceManagementPage })))
const SubscriptionPage = lazy(() => import('../pages/SubscriptionPage').then((module) => ({ default: module.SubscriptionPage })))
const AccountPage = lazy(() => import('../pages/AccountPage').then((module) => ({ default: module.AccountPage })))
const UserManagementPage = lazy(() => import('../pages/admin/UserManagementPage').then((module) => ({ default: module.UserManagementPage })))
const DeviceFleetPage = lazy(() => import('../pages/admin/DeviceFleetPage').then((module) => ({ default: module.DeviceFleetPage })))
const TemplateManagementPage = lazy(() => import('../pages/admin/TemplateManagementPage').then((module) => ({ default: module.TemplateManagementPage })))
const FirmwareManagementPage = lazy(() => import('../pages/admin/FirmwareManagementPage').then((module) => ({ default: module.FirmwareManagementPage })))
const PlanManagementPage = lazy(() => import('../pages/admin/PlanManagementPage').then((module) => ({ default: module.PlanManagementPage })))
const AuditLogPage = lazy(() => import('../pages/admin/AuditLogPage').then((module) => ({ default: module.AuditLogPage })))
const SystemSettingsPage = lazy(() => import('../pages/admin/SystemSettingsPage').then((module) => ({ default: module.SystemSettingsPage })))

function LazyBoundary({ children }: { children: ReactNode }) {
  return <Suspense fallback={<div className="route-loading" role="status" aria-label="正在加载页面"><span className="loading-dot" /></div>}>{children}</Suspense>
}

export function RequireAuth() {
  const status = useAuthStore((state) => state.status)
  const location = useLocation()
  if (status === 'initializing') {
    return <div className="route-loading" role="status" aria-label="正在验证登录状态"><span className="loading-dot" /></div>
  }
  if (status === 'anonymous') {
    const redirect = `${location.pathname}${location.search}${location.hash}`
    return <Navigate to={`/login?redirect=${encodeURIComponent(redirect)}`} replace />
  }
  return <Outlet />
}

export function RequirePermission({ permission, redirectTo = '/dashboard' }: { permission: string; redirectTo?: string }) {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  return hasPermission(permission)
    ? <Outlet />
    : <Navigate to={redirectTo} replace />
}

export function RequireAdmin() {
  return <RequireRoutePermission routeKey="users" />
}

export function RequireRoutePermission({ routeKey }: { routeKey: ConsoleRouteKey }) {
  const permission = consoleRouteByKey(routeKey).permission
  if (!permission) return <Outlet />
  return <RequirePermission permission={permission} />
}

export function RequireAdminRedirect({ to, preserveSearch = false }: { to: string; preserveSearch?: boolean }) {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const location = useLocation()
  const permission = consoleRouteByKey('users').permission
  if (!permission || !hasPermission(permission)) return <Navigate to="/dashboard" replace />
  if (!preserveSearch) return <Navigate to={to} replace />

  const separator = to.indexOf('?')
  const pathname = separator === -1 ? to : to.slice(0, separator)
  const targetSearch = separator === -1 ? '' : to.slice(separator + 1)
  const mergedSearch = new URLSearchParams(location.search)
  new URLSearchParams(targetSearch).forEach((value, key) => mergedSearch.set(key, value))
  return <Navigate to={{ pathname, search: mergedSearch.toString() }} replace />
}

export function LegacyModelsRedirect() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const permission = consoleRouteByKey('models').permission
  return <Navigate to={permission && hasPermission(permission) ? '/admin/models' : '/profiles'} replace />
}

function RouteErrorBoundary() {
  const error = useRouteError()
  const navigate = useNavigate()
  return (
    <main className="route-result">
      <h1>页面暂时无法打开</h1>
      <p>{error instanceof Error ? error.message : '请稍后重试'}</p>
      <button type="button" onClick={() => navigate('/dashboard')}>返回首页</button>
    </main>
  )
}

const pageElements: Record<ConsoleRouteKey, ReactNode> = {
  dashboard: <DashboardPage />,
  devices: <DeviceListPage />,
  deviceDetail: <DeviceDetailPage />,
  profiles: <ProfileListPage />,
  profileEditor: <ProfileEditorPage />,
  memories: <MemoryPage />,
  subscription: <SubscriptionPage />,
  account: <AccountPage />,
  voices: <VoiceManagementPage />,
  models: <ModelManagementPage />,
  capabilities: <CapabilityManagementPage />,
  templates: <TemplateManagementPage />,
  users: <UserManagementPage />,
  adminDevices: <DeviceFleetPage />,
  plans: <PlanManagementPage />,
  firmware: <FirmwareManagementPage />,
  audit: <AuditLogPage />,
  systemSettings: <SystemSettingsPage />,
}

const configuredRoutes = consoleRoutes.map((route) => {
  const page = <LazyBoundary>{pageElements[route.key]}</LazyBoundary>
  if (!route.permission) return { path: route.path.slice(1), element: page }
  return {
    path: route.path.slice(1),
    element: <RequireRoutePermission routeKey={route.key} />,
    children: [{ index: true, element: page }],
  }
})

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LazyBoundary><LoginPage /></LazyBoundary>,
    errorElement: <RouteErrorBoundary />,
  },
  {
    path: '/register',
    element: <LazyBoundary><RegisterPage /></LazyBoundary>,
    errorElement: <RouteErrorBoundary />,
  },
  {
    element: <RequireAuth />,
    errorElement: <RouteErrorBoundary />,
    children: [{
      path: '/',
      element: <LazyBoundary><AppShell /></LazyBoundary>,
      children: [
        { index: true, element: <Navigate to="/dashboard" replace /> },
        ...configuredRoutes,
        { path: 'models', element: <LegacyModelsRedirect /> },
        { path: 'admin/voice-clones', element: <Navigate to="/voices?tab=clone" replace /> },
        { path: 'admin/voices', element: <RequireAdminRedirect to="/voices?tab=timbres" preserveSearch /> },
        { path: 'admin/voice-resources', element: <RequireAdminRedirect to="/voices?tab=timbres" preserveSearch /> },
        { path: 'admin/resources', element: <RequireAdminRedirect to="/admin/models" /> },
        { path: 'admin', element: <Navigate to="/admin/users" replace /> },
        { path: '*', element: <p role="alert" className="route-not-found">页面不存在</p> },
      ],
    }],
  },
])
