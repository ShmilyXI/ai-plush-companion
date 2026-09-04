import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import test from 'node:test'

function trackedPaths() {
  return execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8' }).split('\0').filter(Boolean)
}

test('python runtime uses only the zixuan source path', () => {
  const paths = trackedPaths()

  assert.ok(paths.some((path) => path === 'server/main/zixuan-server/app.py'))
  assert.equal(paths.some((path) => path.startsWith('server/main/xiaozhi-server/')), false)
})

test('python deployment metadata uses zixuan identities', () => {
  const runtimeRoot = existsSync('server/main/zixuan-server')
    ? 'server/main/zixuan-server'
    : 'server/main/xiaozhi-server'
  const runtimeCompose = readFileSync(`${runtimeRoot}/docker-compose.yml`, 'utf8')
  const logger = readFileSync(`${runtimeRoot}/config/logger.py`, 'utf8')
  const dockerfile = readFileSync('server/Dockerfile-server', 'utf8')

  assert.match(runtimeCompose, /zixuan-server:/)
  assert.match(runtimeCompose, /image:\s+ai-plush\/zixuan-server:/)
  assert.match(runtimeCompose, /container_name:\s+zixuan-server/)
  assert.match(logger, /zixuan-server\.log/)
  assert.match(dockerfile, /^WORKDIR \/opt\/zixuan-server$/m)
})

test('generated runtime api document uses the zixuan filename', () => {
  const paths = trackedPaths()

  assert.ok(paths.includes('docs/api/zixuan-server-openapi.json'))
  assert.equal(paths.includes('docs/api/xiaozhi-server-openapi.json'), false)
})

test('python application metadata uses the zixuan identity', () => {
  const config = readFileSync('server/main/zixuan-server/config.yaml', 'utf8')
  const connection = readFileSync('server/main/zixuan-server/core/connection.py', 'utf8')
  const deviceMcp = readFileSync('server/main/zixuan-server/core/providers/tools/device_mcp/mcp_handler.py', 'utf8')
  const endpointMcp = readFileSync('server/main/zixuan-server/core/providers/tools/mcp_endpoint/mcp_endpoint_handler.py', 'utf8')

  assert.match(config, /^zixuan:\s*$/m)
  assert.doesNotMatch(config, /^xiaozhi:\s*$/m)
  assert.match(connection, /self\.config\["zixuan"\]/)
  assert.match(deviceMcp, /"name": "ZixuanClient"/)
  assert.match(endpointMcp, /"name": "ZixuanMCPEndpointClient"/)
})
