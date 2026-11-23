import type { Metadata, Viewport } from 'next'
import './globals.css'
import { THEME_BOOT_SCRIPT } from '@/lib/theme'

export const metadata: Metadata = {
  title: '紫萱',
  description: '和你的陪伴角色聊天，打字或者直接开口说',
}

// viewportFit: cover 让页面延伸到刘海和底部横条下面，再用 safe-area 留出边距。
// 不禁用缩放：输入框字号不小于 16px，iOS 不会在聚焦时自动放大页面。
export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#f8f9fb' },
    { media: '(prefers-color-scheme: dark)', color: '#0e0f13' },
  ],
}

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  // 主题由一小段脚本在绘制前写到 <html> 上，服务端渲染时不知道，所以这里不让 React 报属性不一致。
  return <html lang="zh-CN" suppressHydrationWarning>
    <head><script dangerouslySetInnerHTML={{ __html: THEME_BOOT_SCRIPT }} /></head>
    <body>{children}</body>
  </html>
}
