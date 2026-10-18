import type { Metadata } from 'next'
import './globals.css'

export const metadata: Metadata = {
  title: '紫萱',
  description: '与陪伴角色进行文字和语音对话',
}

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="zh-CN"><body>{children}</body></html>
}
