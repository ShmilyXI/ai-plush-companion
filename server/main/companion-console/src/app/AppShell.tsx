import {
  ApiOutlined,
  AuditOutlined,
  BellOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DesktopOutlined,
  IdcardOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  RobotOutlined,
  ScissorOutlined,
  SettingOutlined,
  SoundOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Avatar, Button, Drawer, Dropdown, Layout, Menu, Space, Typography, type MenuProps } from 'antd'
import type { ItemType } from 'antd/es/menu/interface'
import { useEffect, useMemo, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'

import { useAuthStore } from '../auth/authStore'
import { AppThemeProvider } from './AppThemeProvider'

const { Header, Sider, Content } = Layout

function useMediaQuery(query: string) {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches)
  useEffect(() => {
    const media = window.matchMedia(query)
    const update = () => setMatches(media.matches)
    update()
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [query])
  return matches
}

const ordinaryItems: ItemType[] = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '首页' },
  { key: '/devices', icon: <DesktopOutlined />, label: '我的设备' },
  { key: '/profiles', icon: <RobotOutlined />, label: '陪伴角色' },
  { key: '/memories', icon: <AuditOutlined />, label: '记忆' },
  { key: '/subscription', icon: <IdcardOutlined />, label: '订阅' },
]

const voiceCloneItem: ItemType = { key: '/admin/voice-clones', icon: <ScissorOutlined />, label: '音色克隆' }

const adminItems: ItemType[] = [
  { type: 'divider' },
  { key: '/admin/models', icon: <ApiOutlined />, label: '模型管理' },
  { key: '/admin/voices', icon: <SoundOutlined />, label: '音色管理' },
  { key: '/admin/voice-resources', icon: <DatabaseOutlined />, label: '音色资源' },
  { key: '/admin/users', icon: <TeamOutlined />, label: '用户管理' },
  { key: '/admin/devices', icon: <DesktopOutlined />, label: '设备总览' },
  { key: '/admin/templates', icon: <RobotOutlined />, label: '角色模板' },
  { key: '/admin/resources', icon: <SettingOutlined />, label: '资源管理' },
  { key: '/admin/firmware', icon: <SettingOutlined />, label: '固件管理' },
  { key: '/admin/plans', icon: <IdcardOutlined />, label: '套餐管理' },
  { key: '/admin/audit', icon: <AuditOutlined />, label: '审计日志' },
  { key: '/admin/system-settings', icon: <SettingOutlined />, label: '系统设置' },
]

export function AppShell() {
  const [collapsed, setCollapsed] = useState(false)
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const isMobile = useMediaQuery('(max-width: 991px)')
  const navigate = useNavigate()
  const location = useLocation()
  const user = useAuthStore((state) => state.user)
  const logout = useAuthStore((state) => state.logout)
  const hasPermission = useAuthStore((state) => state.hasPermission)
  const canCloneVoice = hasPermission('sys:role:normal')
  const canAdminister = hasPermission('sys:role:superAdmin')
  const selectedMenuKey = location.pathname.startsWith('/devices/')
    ? '/devices'
    : location.pathname.startsWith('/profiles/') ? '/profiles' : location.pathname
  const menuItems = useMemo(
    () => {
      const userItems = canCloneVoice ? [...ordinaryItems, voiceCloneItem] : ordinaryItems
      return canAdminister ? [...userItems, ...adminItems] : userItems
    },
    [canAdminister, canCloneVoice],
  )
  const profileItems: MenuProps['items'] = [
    { key: 'account', icon: <UserOutlined />, label: '账号资料' },
    { type: 'divider' },
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true },
  ]

  async function handleProfileAction({ key }: { key: string }) {
    if (key === 'logout') {
      if (loggingOut) return
      setLoggingOut(true)
      try {
        await logout()
        navigate('/login', { replace: true })
      } finally {
        setLoggingOut(false)
      }
      return
    }
    navigate('/account')
  }

  function navigateFromMenu(key: string) {
    navigate(key)
    setMobileNavigationOpen(false)
  }

  const navigation = (
    <>
      <div className="brand" aria-label="陪伴管理台">
        <span className="brand-mark"><RobotOutlined /></span>
        <span>陪伴管理台</span>
      </div>
      <Menu
        mode="inline"
        items={menuItems}
        selectedKeys={[selectedMenuKey]}
        onClick={({ key }) => navigateFromMenu(key)}
      />
    </>
  )

  return (
    <AppThemeProvider><Layout className="app-shell">
      {isMobile ? (
        <Drawer
          className="mobile-nav-drawer"
          rootClassName="mobile-nav-drawer-root"
          placement="left"
          width="min(86vw, 300px)"
          open={mobileNavigationOpen}
          onClose={() => setMobileNavigationOpen(false)}
          closable={false}
          styles={{ body: { padding: '18px 12px' } }}
        >
          {navigation}
        </Drawer>
      ) : (
        <Sider
          className="app-sider"
          collapsed={collapsed}
          collapsedWidth={72}
          trigger={null}
          width={240}
        >
          {navigation}
        </Sider>
      )}
      <Layout className="app-main">
        <Header className="app-header">
          <Button
            type="text"
            className="menu-trigger"
            aria-label={isMobile ? '打开导航' : collapsed ? '展开导航' : '收起导航'}
            icon={isMobile || collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => isMobile
              ? setMobileNavigationOpen(true)
              : setCollapsed((value) => !value)}
          />
          <Space size="middle">
            <Button type="text" aria-label="通知" icon={<BellOutlined />} />
            <Dropdown menu={{ items: profileItems, onClick: ({ key }) => void handleProfileAction({ key }) }} trigger={['click']}>
              <Button type="text" className="profile-button" loading={loggingOut}>
                <Avatar size="small" icon={<UserOutlined />} />
                <Typography.Text>{user?.username}</Typography.Text>
              </Button>
            </Dropdown>
          </Space>
        </Header>
        <Content className="app-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout></AppThemeProvider>
  )
}
