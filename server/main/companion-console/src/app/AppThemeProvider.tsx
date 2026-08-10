import { ConfigProvider, type ThemeConfig } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import type { ReactNode } from 'react'

// eslint-disable-next-line react-refresh/only-export-components -- Keep the public theme contract beside its provider during the migration.
export const consoleTheme: ThemeConfig = {
  token: {
    colorPrimary: '#1677ff',
    colorInfo: '#1677ff',
    colorBgBase: '#ffffff',
    colorBgLayout: '#f5f5f5',
    colorText: '#262626',
    colorTextSecondary: '#595959',
    borderRadius: 6,
    borderRadiusLG: 8,
    controlHeight: 32,
    fontFamily: 'Inter, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
  },
  components: {
    Layout: { headerBg: '#ffffff', siderBg: '#001529' },
    Menu: {
      darkItemBg: '#001529',
      darkItemColor: '#d9e6f2',
      darkItemHoverColor: '#ffffff',
      darkItemSelectedBg: '#1677ff',
      darkItemSelectedColor: '#ffffff',
      darkGroupTitleColor: 'rgba(255, 255, 255, 0.72)',
    },
    Button: { primaryShadow: 'none' },
  },
}

// eslint-disable-next-line react-refresh/only-export-components -- ProLayout and its tests share one visual contract.
export const consoleLayoutToken = {
  header: {
    colorBgHeader: '#ffffff',
    colorBgScrollHeader: '#ffffff',
    colorHeaderTitle: '#262626',
    colorTextMenu: '#595959',
    colorTextMenuActive: '#262626',
    colorTextMenuSelected: '#1677ff',
    colorTextMenuSecondary: '#8c8c8c',
    colorTextRightActionsItem: '#262626',
  },
  sider: {
    colorMenuBackground: '#001529',
    colorTextMenuTitle: '#ffffff',
    colorTextMenu: '#d9e6f2',
    colorTextMenuSecondary: 'rgba(255, 255, 255, 0.72)',
    colorTextMenuSelected: '#ffffff',
    colorTextMenuItemHover: '#ffffff',
    colorTextMenuActive: '#ffffff',
    colorTextSubMenuSelected: '#ffffff',
    colorBgMenuItemSelected: '#1677ff',
    colorBgMenuItemHover: 'rgba(255, 255, 255, 0.10)',
    colorBgMenuItemActive: 'rgba(255, 255, 255, 0.14)',
    colorBgCollapsedButton: '#001529',
    colorTextCollapsedButton: '#d9e6f2',
    colorTextCollapsedButtonHover: '#ffffff',
  },
  pageContainer: {
    colorBgPageContainer: '#f5f5f5',
    colorBgPageContainerFixed: '#ffffff',
  },
} as const

export function AppThemeProvider({ children }: { children: ReactNode }) {
  return <ConfigProvider locale={zhCN} theme={consoleTheme}>{children}</ConfigProvider>
}
