import type { Metadata, Viewport } from 'next'
import './globals.css'

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
  return <html lang="zh-CN"><body>{children}</body></html>
}
