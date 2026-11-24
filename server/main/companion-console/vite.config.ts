import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const productApiPrefix = '/zixuan'

export default defineConfig({
  plugins: [react()],
  build: {
    manifest: true,
  },
  server: {
    host: '0.0.0.0',
    port: 8001,
    proxy: {
      [productApiPrefix]: 'http://127.0.0.1:8002',
    },
  },
  test: {
    environment: 'jsdom',
    environmentOptions: {
      jsdom: {
        url: 'http://localhost/',
      },
    },
    setupFiles: './src/test/setup.ts',
    css: true,
    exclude: ['e2e/**', 'node_modules/**'],
    maxWorkers: 1,
    minWorkers: 1,
    // Ant Design pages under jsdom run about 3x slower on CI runners than locally; a 1.7s test
    // there crossed the 5s default.
    testTimeout: 20_000,
    // Several tests read async UI state right after an interaction and only lose that race on the
    // slower CI runner (each run surfaced a different one). Retry there; a real failure still fails
    // all three attempts, and local runs never retry.
    retry: process.env.CI ? 2 : 0,
  },
})
