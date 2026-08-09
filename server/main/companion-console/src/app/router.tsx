/* eslint-disable react-refresh/only-export-components */
import { lazy, Suspense, type ReactNode } from 'react'
import { createBrowserRouter, Navigate, Outlet, useLocation, useNavigate, useRouteError } from 'react-router-dom'

import { useAuthStore } from '../auth/authStore'

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
const TimbreManagementPage = lazy(() => import('../pages/voices/TimbreManagementPage').then((module) => ({ default: module.TimbreManagementPage })))
const VoiceResourcePage = lazy(() => import('../pages/voices/VoiceResourcePage').then((module) => ({ default: module.VoiceResourcePage })))
const VoiceClonePage = lazy(() => import('../pages/voices/VoiceClonePage').then((module) => ({ default: module.VoiceClonePage })))
const SubscriptionPage = lazy(() => import('../pages/SubscriptionPage').then((module) => ({ default: module.SubscriptionPage })))
const AccountPage = lazy(() => import('../pages/AccountPage').then((module) => ({ default: module.AccountPage })))
const UserManagementPage = lazy(() => import('../pages/admin/UserManagementPage').then((module) => ({ default: module.UserManagementPage })))
const DeviceFleetPage = lazy(() => import('../pages/admin/DeviceFleetPage').then((module) => ({ default: module.DeviceFleetPage })))
const TemplateManagementPage = lazy(() => import('../pages/admin/TemplateManagementPage').then((module) => ({ default: module.TemplateManagementPage })))
const ResourceManagementPage = lazy(() => import('../pages/admin/ResourceManagementPage').then((module) => ({ default: module.ResourceManagementPage })))
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
  return <RequirePermission permission="sys:role:superAdmin" />
}

export function LegacyModelsRedirect() {
  const hasPermission = useAuthStore((state) => state.hasPermission)
  return <Navigate to={hasPermission('sys:role:superAdmin') ? '/admin/models' : '/profiles'} replace />
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

const ordinaryRoutes = [
  { index: true, element: <Navigate to="/dashboard" replace /> },
  { path: 'dashboard', element: <LazyBoundary><DashboardPage /></LazyBoundary> },
  { path: 'devices', element: <LazyBoundary><DeviceListPage /></LazyBoundary> },
  { path: 'devices/:id', element: <LazyBoundary><DeviceDetailPage /></LazyBoundary> },
  { path: 'profiles', element: <LazyBoundary><ProfileListPage /></LazyBoundary> },
  { path: 'profiles/:id', element: <LazyBoundary><ProfileEditorPage /></LazyBoundary> },
  { path: 'memories', element: <LazyBoundary><MemoryPage /></LazyBoundary> },
  { path: 'models', element: <LegacyModelsRedirect /> },
  { path: 'subscription', element: <LazyBoundary><SubscriptionPage /></LazyBoundary> },
  { path: 'account', element: <LazyBoundary><AccountPage /></LazyBoundary> },
]

const adminRoutes = [
  { index: true, element: <Navigate to="/admin/users" replace /> },
  { path: 'models', element: <LazyBoundary><ModelManagementPage /></LazyBoundary> },
  { path: 'voices', element: <LazyBoundary><TimbreManagementPage /></LazyBoundary> },
  { path: 'voice-resources', element: <LazyBoundary><VoiceResourcePage /></LazyBoundary> },
  { path: 'users', element: <LazyBoundary><UserManagementPage /></LazyBoundary> },
  { path: 'devices', element: <LazyBoundary><DeviceFleetPage /></LazyBoundary> },
  { path: 'templates', element: <LazyBoundary><TemplateManagementPage /></LazyBoundary> },
  { path: 'resources', element: <LazyBoundary><ResourceManagementPage /></LazyBoundary> },
  { path: 'firmware', element: <LazyBoundary><FirmwareManagementPage /></LazyBoundary> },
  { path: 'plans', element: <LazyBoundary><PlanManagementPage /></LazyBoundary> },
  { path: 'audit', element: <LazyBoundary><AuditLogPage /></LazyBoundary> },
  { path: 'system-settings', element: <LazyBoundary><SystemSettingsPage /></LazyBoundary> },
]

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
        ...ordinaryRoutes,
        {
          path: 'admin',
          children: [
            {
              element: <RequirePermission permission="sys:role:normal" />,
              children: [{ path: 'voice-clones', element: <LazyBoundary><VoiceClonePage /></LazyBoundary> }],
            },
            {
              element: <RequireAdmin />,
              children: adminRoutes,
            },
          ],
        },
        { path: '*', element: <p role="alert" className="route-not-found">页面不存在</p> },
      ],
    }],
  },
])
