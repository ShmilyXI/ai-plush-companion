import type { NextConfig } from 'next'

const nextConfig: NextConfig = {
  poweredByHeader: false,
  reactStrictMode: true,
  // 开发模式下允许从哪些域名打开页面。经公网域名调试时把域名写进 PUBLIC_SITE_HOSTS。
  allowedDevOrigins: ['127.0.0.1', 'localhost', ...(process.env.PUBLIC_SITE_HOSTS || '').split(',').map((item) => item.trim()).filter(Boolean)],
  turbopack: { root: process.cwd() },
}

export default nextConfig
