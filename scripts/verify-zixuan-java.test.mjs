import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function trackedPaths() {
  return execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8' }).split('\0').filter(Boolean)
}

test('java source and tests use the zixuan package root', () => {
  const paths = trackedPaths()

  assert.ok(paths.some((path) => path === 'server/main/manager-api/src/main/java/zixuan/AdminApplication.java'))
  assert.ok(paths.some((path) => path.startsWith('server/main/manager-api/src/test/java/zixuan/')))
  assert.equal(paths.some((path) => path.startsWith('server/main/manager-api/src/main/java/xiaozhi/')), false)
  assert.equal(paths.some((path) => path.startsWith('server/main/manager-api/src/test/java/xiaozhi/')), false)
})

test('maven artifact and application metadata use zixuan', () => {
  const pom = readFileSync('server/main/manager-api/pom.xml', 'utf8')
  const application = readFileSync('server/main/manager-api/src/main/java/zixuan/AdminApplication.java', 'utf8')

  assert.match(pom, /<groupId>zixuan<\/groupId>/)
  assert.match(pom, /<artifactId>zixuan-manager-api<\/artifactId>/)
  assert.match(pom, /<name>zixuan-manager-api<\/name>/)
  assert.match(pom, /<description>紫萱后台管理系统<\/description>/)
  assert.match(application, /^package zixuan;/m)
})

test('java and mapper sources contain no retired package namespace', () => {
  const paths = trackedPaths().filter((path) => path.startsWith('server/main/manager-api/'))
    .filter((path) => /\.(?:java|xml|yml|yaml|properties)$/u.test(path))
    .filter((path) => !path.includes('/src/main/resources/db/changelog/'))
  const violations = paths.filter((path) => /\bxiaozhi\./iu.test(readFileSync(path, 'utf8')))

  assert.deepEqual(violations, [])
})

test('product java class names contain no retired identity', () => {
  const paths = trackedPaths().filter((path) => path.startsWith('server/main/manager-api/src/'))
  assert.equal(paths.some((path) => /xiaozhi/iu.test(path)), false)
})
