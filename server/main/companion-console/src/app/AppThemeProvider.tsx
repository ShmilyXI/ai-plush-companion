import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import type { ReactNode } from 'react'

export function AppThemeProvider({ children }: { children: ReactNode }) {
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#167c72',
          colorInfo: '#167c72',
          colorText: '#20302e',
          colorTextSecondary: '#5d706d',
          colorBgBase: '#fbfcfa',
          colorBgLayout: '#f2f6f4',
          borderRadius: 12,
          borderRadiusLG: 18,
          controlHeight: 42,
          fontFamily: 'Inter, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
        },
        components: {
          Layout: { headerBg: 'rgba(251, 252, 250, 0.9)', siderBg: '#fbfcfa' },
          Menu: { itemBg: 'transparent', itemSelectedBg: '#e1f1ed', itemSelectedColor: '#12685f' },
          Button: { primaryShadow: '0 8px 20px rgba(22, 124, 114, 0.18)' },
        },
      }}
    >
      {children}
    </ConfigProvider>
  )
}
