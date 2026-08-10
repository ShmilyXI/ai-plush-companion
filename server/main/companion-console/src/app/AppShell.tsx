import { LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { ProLayout } from '@ant-design/pro-components'
import { Avatar, Dropdown, type MenuProps } from 'antd'
import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'

import { useAuthStore } from '../auth/authStore'
import { AppThemeProvider, consoleLayoutToken } from './AppThemeProvider'
import { layoutRoutesForPermissions, selectedMenuPath } from './navigation'

type MobileMenuActionRegistrationProps = {
  actions: Map<string, () => void>
  path: string
  isMobile: boolean
  onCollapse: () => void
  children: ReactNode
}

export function MobileMenuActionRegistration({
  actions,
  path,
  isMobile,
  onCollapse,
  children,
}: MobileMenuActionRegistrationProps) {
  useEffect(() => {
    if (!isMobile) return
    actions.set(path, onCollapse)
    return () => {
      if (actions.get(path) === onCollapse) actions.delete(path)
    }
  }, [actions, isMobile, onCollapse, path])

  return <>{children}</>
}

export function AppShell() {
  const [loggingOut, setLoggingOut] = useState(false)
  const loggingOutRef = useRef(false)
  const mobileMenuCollapseActions = useRef(new Map<string, () => void>())
  const navigate = useNavigate()
  const location = useLocation()
  const user = useAuthStore((state) => state.user)
  const logout = useAuthStore((state) => state.logout)
  const permissions = useAuthStore((state) => state.permissions)
  const layoutRoutes = useMemo(
    () => layoutRoutesForPermissions((permission) => permissions.includes(permission)),
    [permissions],
  )
  const profileItems = useMemo<MenuProps['items']>(() => [
    { key: 'account', icon: <UserOutlined />, label: '账号资料' },
    { type: 'divider' },
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true },
  ], [])

  async function handleProfileAction({ key }: { key: string }) {
    if (key === 'account') {
      navigate('/account')
      return
    }
    if (loggingOutRef.current) return
    loggingOutRef.current = true
    setLoggingOut(true)
    try {
      await logout()
      navigate('/login', { replace: true })
    } finally {
      loggingOutRef.current = false
      setLoggingOut(false)
    }
  }

  return (
    <AppThemeProvider>
      <ProLayout
        title="AI 陪伴管理台"
        logo={false}
        layout="side"
        siderMenuType="group"
        navTheme="light"
        fixedHeader
        fixSiderbar
        location={{ pathname: location.pathname }}
        route={{ path: '/', children: layoutRoutes }}
        menuItemRender={(item, defaultDom) => {
          const responsiveItem = item as typeof item & { isMobile?: boolean }
          if (!item.path) return defaultDom
          return (
            <MobileMenuActionRegistration
              actions={mobileMenuCollapseActions.current}
              path={item.path}
              isMobile={responsiveItem.isMobile === true}
              onCollapse={item.onClick}
            >
              {defaultDom}
            </MobileMenuActionRegistration>
          )
        }}
        menuProps={{
          selectedKeys: [selectedMenuPath(location.pathname)],
          onClick: ({ key }) => {
            navigate(key)
            mobileMenuCollapseActions.current.get(key)?.()
          },
        }}
        avatarProps={{
          title: user?.username,
          render: () => (
            <Dropdown
              menu={{
                items: profileItems,
                onClick: ({ key }) => void handleProfileAction({ key }),
              }}
              trigger={['click']}
            >
              <button type="button" className="profile-button" disabled={loggingOut}>
                <Avatar size="small" icon={<UserOutlined />} />
                <span>{user?.username}</span>
              </button>
            </Dropdown>
          ),
        }}
        token={consoleLayoutToken}
      >
        <Outlet />
      </ProLayout>
    </AppThemeProvider>
  )
}
