import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  build: {
    manifest: true,
  },
  server: {
    host: '0.0.0.0',
    port: 8001,
    proxy: {
      '/xiaozhi': 'http://127.0.0.1:8002',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: true,
  },
})
