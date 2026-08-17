import assert from 'node:assert/strict'
import test from 'node:test'

import { createDefaultModelConfig, normalizeModelField } from '../src/components/modelFieldUtils.mjs'

test('normalizes label and value options as a select', () => {
  assert.deepEqual(normalizeModelField({
    key: 'llm_model_id',
    label: '记忆 LLM',
    type: 'string',
    options: [{ label: '智谱 GLM', value: 'LLM_GLM' }],
    help: '选择已启用模型',
  }), {
    prop: 'llm_model_id',
    label: '记忆 LLM',
    control: 'select',
    inputType: 'text',
    type: 'select',
    options: [{ label: '智谱 GLM', value: 'LLM_GLM' }],
    defaultValue: '',
    help: '选择已启用模型',
    placeholder: '请输入llm_model_id',
  })
})

test('normalizes boolean and dictionary controls', () => {
  const booleanField = normalizeModelField({
    key: 'send_dimensions',
    label: '发送 dimensions',
    type: 'boolean',
    default: true,
    help: '关闭后不发送维度参数',
  })
  assert.equal(booleanField.control, 'switch')
  assert.equal(booleanField.type, 'switch')
  assert.equal(booleanField.defaultValue, true)
  assert.equal(booleanField.help, '关闭后不发送维度参数')
  assert.equal(normalizeModelField({ key: 'headers', label: '请求头', type: 'dict', default: {} }).control, 'json-textarea')
})

test('keeps primitive options usable', () => {
  assert.deepEqual(
    normalizeModelField({ key: 'language', label: '语言', type: 'string', options: ['zh', 'en'] }).options,
    [{ label: 'zh', value: 'zh' }, { label: 'en', value: 'en' }],
  )
})

test('creates new model configuration from provider defaults', () => {
  const fields = [
    normalizeModelField({ key: 'temperature', type: 'number', default: 0.7 }),
    normalizeModelField({ key: 'thinking_enabled', type: 'boolean', default: false }),
  ]

  assert.deepEqual(createDefaultModelConfig(fields), {
    temperature: 0.7,
    thinking_enabled: false,
  })
})
