import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(path, import.meta.url), 'utf8')
}

test('model management exposes Embedding navigation', () => {
  const page = source('../src/views/ModelConfig.vue')
  assert.match(page, /index="embedding"/)
  assert.match(page, /modelConfig\.embedding/)
})

test('provider management exposes the Embedding model type', () => {
  const page = source('../src/views/ProviderManagement.vue')
  assert.match(page, /value:\s*"Embedding"/)
  assert.match(page, /providerManagement\.modelType\.Embedding/)
})

test('both model dialogs render select and boolean controls from provider metadata', () => {
  for (const file of ['../src/components/AddModelDialog.vue', '../src/components/ModelEditDialog.vue']) {
    const dialog = source(file)
    assert.match(dialog, /field\.control === 'select'/)
    assert.match(dialog, /field\.control === 'switch'/)
    assert.match(dialog, /field\.options/)
  }
})

test('all manager translations name Embedding models', () => {
  for (const file of ['../src/i18n/zh_CN.js', '../src/i18n/en.js', '../src/i18n/vi.js']) {
    const messages = source(file)
    assert.match(messages, /'modelConfig\.embedding'/)
    assert.match(messages, /'providerManagement\.modelType\.Embedding'/)
  }
})
