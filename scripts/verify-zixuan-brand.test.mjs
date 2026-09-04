import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import test from 'node:test'

import { compileAllowlist, scanEntries } from './verify-zixuan-brand.mjs'

const allowlist = compileAllowlist([
  {
    category: 'upstream',
    pathPattern: '^README\\.md$',
    contentPattern: 'https://github\\.com/78/xiaozhi-esp32(?:[\\s)`]|$)',
  },
  {
    category: 'migration-history',
    pathPattern: '^server/main/manager-api/src/main/resources/db/changelog/[^/]+\\.sql$',
    contentPattern: 'xiaozhi_esp32_server',
  },
])

test('rejects product-owned retired names in paths and content', () => {
  const result = scanEntries([
    { path: 'server/main/xiaozhi-server/app.py', text: 'SERVICE_NAME = "xiaozhi-server"\n' },
    { path: 'firmware/main/application.cc', text: 'constexpr auto kName = "小智";\n' },
  ], allowlist)

  assert.equal(result.ok, false)
  assert.deepEqual(result.violations.map((item) => item.kind), ['path', 'content', 'content'])
})

test('allows only matching upstream and immutable migration identities', () => {
  const result = scanEntries([
    { path: 'README.md', text: 'Based on https://github.com/78/xiaozhi-esp32\n' },
    {
      path: 'server/main/manager-api/src/main/resources/db/changelog/202607271200.sql',
      text: 'USE xiaozhi_esp32_server;\n',
    },
  ], allowlist)

  assert.equal(result.ok, true)
  assert.equal(result.allowed.length, 2)
  assert.deepEqual(result.counts.allowedByCategory, { upstream: 1, 'migration-history': 1 })
})

test('does not let a valid pattern escape its configured path', () => {
  const result = scanEntries([
    { path: 'server/main/runtime/config.py', text: 'https://github.com/78/xiaozhi-esp32\n' },
  ], allowlist)

  assert.equal(result.ok, false)
  assert.equal(result.violations[0].path, 'server/main/runtime/config.py')
})

test('rejects malformed or overly broad allowlist entries', () => {
  assert.throws(() => compileAllowlist([{ category: 'upstream', pathPattern: '.*', contentPattern: 'xiaozhi' }]), /pathPattern/)
  assert.throws(() => compileAllowlist([{ category: 'other', pathPattern: '^README', contentPattern: 'xiaozhi' }]), /category/)
})

test('release inventory names every maintained board and runtime boundary', () => {
  const inventory = readFileSync(resolve('docs/zixuan-release-inventory.md'), 'utf8')

  for (const expected of [
    'zhengchen-cam',
    'bread-compact-wifi-s3cam',
    'manager-api',
    'xiaozhi-server',
    'mqtt-gateway',
    '/xiaozhi/v1/',
    '/xiaozhi/ota/',
    'device-server',
    'devices/p2p/',
    'xiaozhi_esp32_server',
    'sys:params',
  ]) {
    assert.match(inventory, new RegExp(expected.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }
})
