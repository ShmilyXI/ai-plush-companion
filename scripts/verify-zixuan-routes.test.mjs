import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import test from 'node:test'

const read = (path) => existsSync(path) ? readFileSync(path, 'utf8') : ''

test('shared runtime contract defines the zixuan routes', () => {
  const path = 'contracts/zixuan-runtime.json'
  assert.ok(existsSync(path), `${path} must exist`)
  const contract = JSON.parse(read(path))

  assert.equal(contract.contractVersion, 'zixuan-cutover-v1')
  assert.deepEqual(contract.routes, {
    manager: '/zixuan',
    websocket: '/zixuan/v1/',
    ota: '/zixuan/ota/',
    playground: '/zixuan/internal/playground',
  })
  assert.deepEqual(contract.identityNeutralRoutes, ['/api/v1', '/internal', '/mcp'])
})

test('server and browser consumers use the zixuan route prefix', () => {
  const files = {
    java: read('server/main/manager-api/src/main/resources/application.yml'),
    python: read('server/main/zixuan-server/config/product_identity.py'),
    console: read('server/main/companion-console/src/api/http.ts'),
    consoleDev: read('server/main/companion-console/vite.config.ts'),
    web: read('server/main/companion-web/lib/server-manager.ts'),
    nginx: read('server/docs/docker/nginx.conf'),
  }

  assert.match(files.java, /context-path:\s*\/zixuan/)
  assert.match(files.python, /PRODUCT_ROUTE_PREFIX\s*=\s*"\/zixuan"/)
  assert.match(files.console, /PRODUCT_API_PREFIX\s*=\s*'\/zixuan'/)
  assert.match(files.console, /VITE_API_BASE_URL \|\| PRODUCT_API_PREFIX/)
  assert.match(files.consoleDev, /productApiPrefix\s*=\s*'\/zixuan'/)
  assert.match(files.consoleDev, /\[productApiPrefix\]:\s*'http:\/\/127\.0\.0\.1:8002'/)
  assert.match(files.web, /8002\/zixuan/)
  assert.match(files.nginx, /location \/zixuan\//)
  assert.match(files.nginx, /return 410/)
})

test('maintained firmware boards use the zixuan ota route', () => {
  for (const board of ['zhengchen-cam', 'bread-compact-wifi-s3cam']) {
    const config = read(`firmware/main/boards/${board}/config.json`)
    assert.match(config, /CONFIG_OTA_URL=.*\/zixuan\/ota\//, board)
  }
})

test('runtime openapi preserves identity-neutral routes', () => {
  const api = JSON.parse(read('docs/api/zixuan-server-openapi.json'))
  const paths = Object.keys(api.paths)

  assert.ok(paths.includes('/api/v1/conversations/{conversation_id}/stream'))
  assert.ok(paths.includes('/internal/device-control'))
  assert.ok(paths.includes('/mcp/vision/explain'))
})
