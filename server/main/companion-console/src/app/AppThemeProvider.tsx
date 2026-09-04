import { ConfigProvider, type ThemeConfig } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import type { ReactNode } from 'react'

// eslint-disable-next-line react-refresh/only-export-components -- Keep the public theme contract beside its provider during the migration.
export const consoleTheme: ThemeConfig = {
  token: {
    colorPrimary: '#0958d9',
    colorInfo: '#0958d9',
    colorBgBase: '#ffffff',
    colorBgLayout: '#f5f7fa',
    colorText: '#12263a',
    colorTextSecondary: '#5d7185',
    borderRadius: 6,
    borderRadiusLG: 8,
    controlHeight: 32,
    fontFamily: 'Inter, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
  },
  components: {
    Layout: { headerBg: '#ffffff', siderBg: '#f7f9fc' },
    Menu: {
      itemBg: '#f7f9fc',
      itemColor: '#5d7185',
      itemHoverColor: '#12263a',
      itemSelectedBg: '#e7edf5',
      itemSelectedColor: '#12263a',
      groupTitleColor: '#7c8fa3',
    },
    Button: { primaryShadow: 'none' },
  },
}

// eslint-disable-next-line react-refresh/only-export-components -- ProLayout and its tests share one visual contract.
export const consoleLayoutToken = {
  header: {
    colorBgHeader: '#ffffff',
    colorBgScrollHeader: '#ffffff',
    colorHeaderTitle: '#12263a',
    colorTextMenu: '#5d7185',
    colorTextMenuActive: '#12263a',
    colorTextMenuSelected: '#12263a',
    colorTextMenuSecondary: '#7c8fa3',
    colorTextRightActionsItem: '#12263a',
  },
  sider: {
    colorMenuBackground: '#f7f9fc',
    colorTextMenuTitle: '#12263a',
    colorTextMenu: '#5d7185',
    colorTextMenuSecondary: '#7c8fa3',
    colorTextMenuSelected: '#12263a',
    colorTextMenuItemHover: '#12263a',
    colorTextMenuActive: '#12263a',
    colorTextSubMenuSelected: '#12263a',
    colorBgMenuItemSelected: '#e7edf5',
    colorBgMenuItemHover: '#eef3f8',
    colorBgMenuItemActive: '#e7edf5',
    colorBgCollapsedButton: '#f7f9fc',
    colorTextCollapsedButton: '#5d7185',
    colorTextCollapsedButtonHover: '#12263a',
  },
  pageContainer: {
    colorBgPageContainer: '#f5f7fa',
    colorBgPageContainerFixed: '#ffffff',
  },
} as const

export function AppThemeProvider({ children }: { children: ReactNode }) {
  return <ConfigProvider locale={zhCN} theme={consoleTheme}>{children}</ConfigProvider>
}
