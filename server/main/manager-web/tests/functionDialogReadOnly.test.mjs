import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/FunctionDialog.vue', import.meta.url), 'utf8')

test('legacy function dialog points to the capability center without save controls', () => {
  assert.match(source, /插件配置已迁移到新版能力中心/)
  assert.match(source, /companion-console/)
  assert.doesNotMatch(source, /@click="saveSelection"/)
  assert.doesNotMatch(source, /functionDialog\.saveConfig/)
})
