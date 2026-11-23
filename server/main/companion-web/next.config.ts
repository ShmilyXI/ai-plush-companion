import type { NextConfig } from 'next'

const nextConfig: NextConfig = {
  poweredByHeader: false,
  reactStrictMode: true,
  // 开发模式下允许从哪些域名打开页面。经公网域名调试时把域名写进 PUBLIC_SITE_HOSTS。
  allowedDevOrigins: ['127.0.0.1', 'localhost', ...(process.env.PUBLIC_SITE_HOSTS || '').split(',').map((item) => item.trim()).filter(Boolean)],
  turbopack: { root: process.cwd() },
  // Live2D 的模型和运行库有好几 MB，而且基本不变。让浏览器缓存一周，再次通话时直接用缓存，不用每次都去问一遍服务器。
  async headers() {
    return [{
      source: '/live2d/:path*',
      headers: [{ key: 'Cache-Control', value: 'public, max-age=604800, stale-while-revalidate=86400' }],
    }]
  },
}

export default nextConfig
