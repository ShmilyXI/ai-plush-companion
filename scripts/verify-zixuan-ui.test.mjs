import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const retiredLatin = ['xiao', 'zhi'].join('')
const retiredChinese = ['小', '智'].join('')
const retiredPattern = new RegExp(`${retiredLatin}|${retiredChinese}`, 'iu')

function trackedPaths() {
  return execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8' }).split('\0').filter(Boolean)
}

function withoutExternalUrls(text) {
  return text.replace(/https?:\/\/[^\s)"'`]+/gu, '')
}

test('console modules and storage use zixuan identity', () => {
  const paths = trackedPaths()
  const source = paths
    .filter((path) => path.startsWith('server/main/companion-console/src/'))
    .map((path) => withoutExternalUrls(readFileSync(path, 'utf8')))
    .join('\n')

  assert.ok(paths.includes('server/main/companion-console/src/api/zixuanModels.ts'))
  assert.equal(paths.includes(`server/main/companion-console/src/api/${retiredLatin}Models.ts`), false)
  assert.doesNotMatch(source, retiredPattern)
  assert.match(source, /zixuan\.playground\.v1/)
})

test('mobile metadata and visible copy use zixuan', () => {
  const pkg = JSON.parse(readFileSync('server/main/manager-mobile/package.json', 'utf8'))
  const manifest = readFileSync('server/main/manager-mobile/src/manifest.json', 'utf8')
  const pages = readFileSync('server/main/manager-mobile/src/pages.json', 'utf8')
  const paths = trackedPaths().filter((path) => path.startsWith('server/main/manager-mobile/src/'))
  const source = paths.map((path) => withoutExternalUrls(readFileSync(path, 'utf8'))).join('\n')

  assert.equal(pkg.name, 'zixuan-mobile-admin')
  assert.match(pkg.description, /紫萱/)
  assert.match(manifest, /"name": "紫萱"/)
  assert.match(pages, /"navigationBarTitleText": "紫萱"/)
  assert.doesNotMatch(source, retiredPattern)
})

test('control plane settings expose zixuan field names', () => {
  const paths = trackedPaths().filter((path) => path.startsWith('server/main/manager-api/src/'))
    .filter((path) => !path.includes('/db/changelog/'))
  const source = paths.map((path) => readFileSync(path, 'utf8')).join('\n')

  assert.match(source, /zixuanListenHost/)
  assert.match(source, /zixuanListenPort/)
  assert.doesNotMatch(source, retiredPattern)
})

test('python defaults present zixuan to users', () => {
  const config = withoutExternalUrls(readFileSync('server/main/zixuan-server/config.yaml', 'utf8'))
  const util = readFileSync('server/main/zixuan-server/core/utils/util.py', 'utf8')

  assert.match(config, /你好紫萱/)
  assert.match(config, /你是紫萱/)
  assert.doesNotMatch(config + util, retiredPattern)
})

test('first-party application titles present the zixuan brand', () => {
  const login = readFileSync('server/main/companion-console/src/pages/LoginPage.tsx', 'utf8')
  const register = readFileSync('server/main/companion-console/src/pages/RegisterPage.tsx', 'utf8')
  const shell = readFileSync('server/main/companion-console/src/app/AppShell.tsx', 'utf8')
  const web = readFileSync('server/main/companion-web/app/layout.tsx', 'utf8')

  assert.match(login, /紫萱管理台/)
  assert.match(register, /紫萱管理台/)
  assert.match(shell, /title="紫萱管理台"/)
  assert.match(web, /title: '紫萱'/)
})
