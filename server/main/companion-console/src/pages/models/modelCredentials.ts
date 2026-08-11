import type { ModelConfig, ModelProvider, ModelProviderField } from '../../api/xiaozhiModels'

export type ModelCredentialStatus = 'configured' | 'missing' | 'not_required'

export function isCredentialField(field: Pick<ModelProviderField, 'key' | 'type'>) {
  const normalized = field.key
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^a-zA-Z0-9]+/g, '_')
    .toLowerCase()
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
  return credentialFields.every((field) => configured.has(field.key)) ? 'configured' : 'missing'
}
