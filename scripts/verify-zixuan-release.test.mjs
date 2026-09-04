import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync, statSync } from 'node:fs'
import test from 'node:test'

const read = (path) => existsSync(path) ? readFileSync(path, 'utf8') : ''
const sha256 = (path) => createHash('sha256').update(readFileSync(path)).digest('hex')
const deployRoot = 'deploy/zixuan'
const unitNames = [
  'zixuan-manager-api',
  'zixuan-server',
  'zixuan-mqtt-gateway',
  'zixuan-companion-web',
  'zixuan-memory',
]

test('zixuan deployment bundle defines every release service', () => {
  for (const name of unitNames) {
    const path = `${deployRoot}/${name}.service`
    const unit = read(path)
    assert.ok(unit, `${path} must exist`)
    assert.match(unit, /EnvironmentFile=-\/etc\/zixuan\/zixuan\.env/)
    assert.match(unit, /WorkingDirectory=\/opt\/zixuan\//)
    assert.doesNotMatch(unit, /xiaozhi/i)
  }

  const env = read(`${deployRoot}/zixuan.env.example`)
  assert.match(env, /ZIXUAN_CONTRACT_VERSION=zixuan-cutover-v1/)
  assert.doesNotMatch(env, /PASSWORD=.+|TOKEN=.+|SECRET=.+/)
})

test('zixuan proxy exposes new contracts and rejects retired routes', () => {
  const nginx = read(`${deployRoot}/nginx.conf`)
  for (const route of ['/zixuan/v1/', '/zixuan/ota/', '/zixuan/', '/api/v1/', '/internal/', '/mcp/']) {
    assert.ok(nginx.includes(route), route)
  }
  assert.match(nginx, /location ~ \^\/xiaozhi\(\?:\/\|\$\)/)
  assert.match(nginx, /return 410/)
})

test('container and release manifests share one immutable contract version', () => {
  const compose = read(`${deployRoot}/docker-compose.yml`)
  assert.match(compose, /zixuan-server:/)
  assert.match(compose, /zixuan-manager-api:/)
  assert.match(compose, /zixuan-mqtt-gateway:/)
  assert.match(compose, /zixuan_esp32_server/)
  assert.match(compose, /context: \.\.\/\.\.\/server\n\s+dockerfile: Dockerfile-web/)
  assert.match(compose, /context: \.\.\/\.\.\/server\n\s+dockerfile: Dockerfile-server/)
  assert.doesNotMatch(compose, /xiaozhi/i)
  for (const dockerfile of ['Dockerfile.mqtt-gateway', 'Dockerfile.companion-web']) {
    const source = read(`${deployRoot}/${dockerfile}`)
    assert.ok(source, `${dockerfile} must exist`)
    assert.doesNotMatch(source, /xiaozhi/i)
  }

  const manifestPath = `${deployRoot}/release-manifest.json`
  assert.ok(existsSync(manifestPath), `${manifestPath} must exist`)
  const manifest = JSON.parse(read(manifestPath))
  assert.equal(manifest.contractVersion, 'zixuan-cutover-v1')
  assert.equal(manifest.status, 'ready')
  for (const artifact of manifest.artifacts) {
    assert.match(artifact.sha256, /^[a-f0-9]{64}$/)
    assert.equal(artifact.contractVersion, manifest.contractVersion)
    if (artifact.kind === 'file') {
      assert.ok(existsSync(artifact.path), artifact.path)
      assert.equal(statSync(artifact.path).size, artifact.size)
      assert.equal(sha256(artifact.path), artifact.sha256)
    }
  }
  assert.deepEqual(manifest.firmware.boards.sort(), ['bread-compact-wifi-s3cam', 'zhengchen-cam'])
})

test('mqtt release package excludes local logs, caches, and tests', () => {
  const pkg = JSON.parse(read('mqtt-gateway/package.json'))
  assert.deepEqual(pkg.files, [
    'app.js',
    'config/mqtt.json.example',
    'ecosystem.config.js',
    'mqtt-protocol.js',
    'product-identity.js',
    'start-local.sh',
    'utils',
  ])
})

test('release verifier and sanitized rehearsal evidence are tracked', () => {
  const verifier = 'scripts/verify-zixuan-release.sh'
  const rehearsal = 'scripts/rehearse-zixuan-release.sh'
  assert.ok(existsSync(verifier))
  assert.ok((statSync(verifier).mode & 0o111) !== 0, 'release verifier must be executable')
  assert.match(read(verifier), /verify-zixuan-brand\.mjs/)
  assert.match(read(verifier), /zixuan-release-manifest\.json/)
  assert.match(read(verifier), /data\/\.config\.yaml/)
  assert.ok(existsSync(rehearsal))
  assert.ok((statSync(rehearsal).mode & 0o111) !== 0, 'release rehearsal must be executable')
  assert.match(read(rehearsal), /mktemp -d/)
  assert.match(read(rehearsal), /zixuan-migrate-storage\.sh/)
  assert.match(read(rehearsal), /zixuan-rollback\.sh/)

  const report = JSON.parse(read(`${deployRoot}/rehearsal-report.json`))
  assert.equal(report.contractVersion, 'zixuan-cutover-v1')
  assert.equal(report.status, 'passed')
  assert.equal(report.sanitized, true)
  assert.doesNotMatch(JSON.stringify(report), /password|token|secret/i)
})
