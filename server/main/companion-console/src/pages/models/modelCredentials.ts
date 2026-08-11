import type { ModelConfig, ModelProvider, ModelProviderField } from '../../api/xiaozhiModels'

export type ModelCredentialStatus = 'configured' | 'missing' | 'not_required'

function normalizedKey(key: string) {
  return key
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^a-zA-Z0-9]+/g, '_')
    .toLowerCase()
}

export function isCredentialField(field: Pick<ModelProviderField, 'key' | 'type'>) {
  const normalized = normalizedKey(field.key)
  return field.type === 'password'
    || /(^|_)(token|secret|password|authorization|credential)($|_)/.test(normalized)
    || normalized.includes('api_key')
    || normalized.includes('access_key_secret')
    || normalized.includes('private_key')
}

export function credentialStatus(model: ModelConfig, provider?: ModelProvider): ModelCredentialStatus {
  const credentialFields = provider?.fields.filter(isCredentialField) ?? []
  if (credentialFields.length === 0) return provider ? 'not_required' : 'missing'
  const configured = new Set(model.configuredSecretPaths)
  const tokenFields = credentialFields.filter((field) => normalizedKey(field.key) === 'token')
  const otherFields = credentialFields.filter((field) => normalizedKey(field.key) !== 'token')
  const tokenConfigured = tokenFields.some((field) => configured.has(field.key))
  const otherCredentialsConfigured = otherFields.length > 0
    && otherFields.every((field) => configured.has(field.key))
  return tokenConfigured || otherCredentialsConfigured ? 'configured' : 'missing'
}
