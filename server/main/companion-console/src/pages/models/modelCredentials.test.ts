import { describe, expect, it } from 'vitest'

import type { ModelConfig, ModelProvider } from '../../api/xiaozhiModels'
import { credentialStatus, isCredentialField } from './modelCredentials'

const provider = (fields: ModelProvider['fields']): ModelProvider => ({
  id: 'provider',
  modelType: 'LLM',
  providerCode: 'openai',
  name: 'OpenAI',
  fields,
  sort: 1,
  updater: null,
  updateDate: null,
  creator: null,
  createDate: null,
})

const model = (paths: string[]): ModelConfig => ({
  id: 'model',
  modelType: 'LLM',
  modelCode: 'model',
  modelName: 'Model',
  isDefault: 0,
  isEnabled: 1,
  configJson: { type: 'openai' },
  docLink: null,
  remark: null,
  sort: 1,
  configuredSecretPaths: paths,
})

describe('modelCredentials', () => {
  it('recognizes password fields and credential-like keys', () => {
    expect(isCredentialField({ key: 'password', type: 'string' })).toBe(true)
    expect(isCredentialField({ key: 'apiKey', type: 'string' })).toBe(true)
    expect(isCredentialField({ key: 'base_url', type: 'string' })).toBe(false)
  })

  it('returns not_required when the provider has no credential fields', () => {
    expect(credentialStatus(model([]), provider([
      { key: 'base_url', label: '地址', type: 'string' },
    ]))).toBe('not_required')
  })

  it('requires every credential field to be configured', () => {
    const secured = provider([
      { key: 'api_key', label: 'API Key', type: 'string' },
      { key: 'secret', label: 'Secret', type: 'password' },
    ])

    expect(credentialStatus(model(['api_key']), secured)).toBe('missing')
    expect(credentialStatus(model(['api_key', 'secret']), secured)).toBe('configured')
  })
})
