import type { AxiosResponse } from 'axios'

import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'

export const modelTypes = ['LLM', 'ASR', 'TTS', 'VAD', 'VLLM', 'Memory'] as const
export type ModelType = typeof modelTypes[number]
export type ModelSource = 'global' | 'private'
export type ModelCatalogAction = 'view' | 'configure' | 'copy' | 'edit' | 'test' | 'enable' | 'disable' | 'delete'
export type ModelProviderFieldType = 'string' | 'number' | 'boolean' | 'dict'
export type CredentialStatus = 'configured' | 'missing' | 'not_required' | 'unknown'
export type CredentialRequirement = 'required' | 'not_required' | 'unknown'

const catalogActions: ModelCatalogAction[] = ['view', 'configure', 'copy', 'edit', 'test', 'enable', 'disable', 'delete']
const providerFieldTypes: ModelProviderFieldType[] = ['string', 'number', 'boolean', 'dict']
const credentialStatuses: CredentialStatus[] = ['configured', 'missing', 'not_required', 'unknown']
const credentialRequirements: CredentialRequirement[] = ['required', 'not_required', 'unknown']

export interface PrivateModel {
  id: string
  modelType: ModelType
  name: string
  providerCode: string
  vendorName: string
  protocol: string
  credentialRequired: boolean
  providerTemplateId: string | null
  source: 'private'
  apiUrl: string | null
  modelId: string | null
  config: Record<string, unknown>
  apiKeyConfigured: boolean
  configuredSecretKeys: string[]
  enabled: boolean
  usageCount: number
}

export interface ModelEditorInput {
  modelType: ModelType
  name: string
  providerCode: string
  vendorName: string
  protocol: string
  credentialRequired: boolean
  providerTemplateId?: string
  apiUrl?: string
  apiKey?: string
  modelId?: string
  config?: Record<string, unknown>
  secrets?: Record<string, unknown>
  clearSecretKeys?: string[]
  enabled: boolean
}

export type PrivateModelInput = ModelEditorInput

export interface ModelCatalogItem {
  id: string
  reference: string
  modelType: ModelType
  name: string
  providerCode: string
  vendorCode: string | null
  vendorName: string | null
  protocol: string | null
  providerTemplateId: string | null
  apiUrl: string | null
  modelId: string | null
  credentialRequirement: CredentialRequirement
  credentialConfigured: boolean
  credentialStatus: CredentialStatus
  keyUrl: string | null
  docsUrl: string | null
  setupGuide: string[]
  credentialFields: ModelProviderField[]
  unavailableReason: string | null
  source: ModelSource
  enabled: boolean
  defaultModel: boolean
  usageCount: number
  actions: ModelCatalogAction[]
}

export interface ModelProviderField {
  key: string
  label: string
  type: ModelProviderFieldType
  required: boolean
  secret: boolean
  options: unknown[]
  defaultValue: unknown
}

export interface ModelProviderTemplate {
  id: string
  modelType: ModelType
  providerCode: string
  name: string
  sort: number | null
  fields: ModelProviderField[]
}

export interface ModelTestResult {
  success: boolean
  message: string
  elapsedMillis: number
}

export interface GlobalModelCredential {
  globalModelId: string
  apiUrl: string | null
  modelId: string | null
  configuredSecretKeys: string[]
  credentialRequirement: CredentialRequirement
  credentialConfigured: boolean
  credentialStatus: CredentialStatus
}

export interface GlobalModelCredentialInput {
  apiUrl?: string
  modelId?: string
  secrets?: Record<string, unknown>
  clearSecretKeys?: string[]
}

interface RequestOptions { signal?: AbortSignal }

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

function optionalString(value: unknown) {
  return value === null || value === undefined || typeof value === 'string'
}

function optionalHttpUrl(value: unknown): value is string | null | undefined {
  if (value === null || value === undefined) return true
  if (typeof value !== 'string') return false
  try {
    const url = new URL(value)
    return (url.protocol === 'http:' || url.protocol === 'https:') && Boolean(url.hostname) && !url.username && !url.password
  } catch { return false }
}

function validUsageCount(value: unknown) {
  const parsed = typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value) ? Number(value) : value
  return typeof parsed === 'number' && Number.isSafeInteger(parsed) && parsed >= 0
}

function unwrap(response: AxiosResponse<ApiResult<unknown>>) {
  const result: unknown = response.data
  if (!isRecord(result) || typeof result.code !== 'number' || typeof result.msg !== 'string' || !('data' in result)) {
    throw new ApiProtocolError('响应数据格式错误', result, response.config)
  }
  if (result.code !== 0) throw new ApiError(result.code, result.msg || '请求失败', result.data, response.config)
  return result.data
}

function parseModelType(value: unknown, response: AxiosResponse) {
  if (!modelTypes.includes(value as ModelType)) throw new ApiProtocolError('模型类型字段错误', value, response.config)
  return value as ModelType
}

function parseSecretKeys(value: unknown, response: AxiosResponse) {
  if (!Array.isArray(value) || value.some((key) => typeof key !== 'string' || !key)) {
    throw new ApiProtocolError('模型凭据状态字段错误', value, response.config)
  }
  return [...new Set(value as string[])]
}

function parseModel(value: unknown, response: AxiosResponse): PrivateModel {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id
    || typeof value.name !== 'string' || !value.name || typeof value.providerCode !== 'string' || !value.providerCode
    || typeof value.vendorName !== 'string' || !value.vendorName || typeof value.protocol !== 'string' || !value.protocol
    || typeof value.credentialRequired !== 'boolean'
    || !optionalString(value.providerTemplateId) || value.source !== 'private' || !optionalString(value.apiUrl)
    || !optionalString(value.modelId) || (value.config !== null && value.config !== undefined && !isRecord(value.config))
    || typeof value.apiKeyConfigured !== 'boolean' || typeof value.enabled !== 'boolean'
    || !validUsageCount(value.usageCount)) {
    throw new ApiProtocolError('私有模型数据字段错误', value, response.config)
  }
  return {
    id: value.id,
    modelType: parseModelType(value.modelType, response),
    name: value.name,
    providerCode: value.providerCode,
    vendorName: value.vendorName,
    protocol: value.protocol,
    credentialRequired: value.credentialRequired,
    providerTemplateId: value.providerTemplateId ?? null,
    source: 'private',
    apiUrl: value.apiUrl ?? null,
    modelId: value.modelId ?? null,
    config: value.config ?? {},
    apiKeyConfigured: value.apiKeyConfigured,
    configuredSecretKeys: parseSecretKeys(value.configuredSecretKeys, response),
    enabled: value.enabled,
    usageCount: Number(value.usageCount),
  }
}

function parseCatalogItem(value: unknown, response: AxiosResponse): ModelCatalogItem {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id || typeof value.reference !== 'string'
    || typeof value.name !== 'string' || !value.name || typeof value.providerCode !== 'string' || !value.providerCode
    || !optionalString(value.vendorCode) || !optionalString(value.vendorName) || !optionalString(value.protocol)
    || !optionalString(value.providerTemplateId) || !optionalString(value.apiUrl) || !optionalString(value.modelId)
    || !credentialRequirements.includes(value.credentialRequirement as CredentialRequirement)
    || typeof value.credentialConfigured !== 'boolean'
    || !credentialStatuses.includes(value.credentialStatus as CredentialStatus)
    || !optionalHttpUrl(value.keyUrl) || !optionalHttpUrl(value.docsUrl)
    || !Array.isArray(value.setupGuide) || value.setupGuide.some((step) => typeof step !== 'string' || !step)
    || !Array.isArray(value.credentialFields) || !optionalString(value.unavailableReason)
    || (value.source !== 'global' && value.source !== 'private') || typeof value.enabled !== 'boolean'
    || typeof value.defaultModel !== 'boolean' || !validUsageCount(value.usageCount) || !Array.isArray(value.actions)
    || value.actions.some((action) => !catalogActions.includes(action as ModelCatalogAction))) {
    throw new ApiProtocolError('模型目录数据字段错误', value, response.config)
  }
  if (value.reference !== `${value.source}:${value.id}`) {
    throw new ApiProtocolError('模型目录引用格式错误', value, response.config)
  }
  return {
    id: value.id,
    reference: value.reference,
    modelType: parseModelType(value.modelType, response),
    name: value.name,
    providerCode: value.providerCode,
    vendorCode: value.vendorCode ?? null,
    vendorName: value.vendorName ?? null,
    protocol: value.protocol ?? null,
    providerTemplateId: value.providerTemplateId ?? null,
    apiUrl: value.apiUrl ?? null,
    modelId: value.modelId ?? null,
    credentialRequirement: value.credentialRequirement as CredentialRequirement,
    credentialConfigured: value.credentialConfigured,
    credentialStatus: value.credentialStatus as CredentialStatus,
    keyUrl: value.keyUrl ?? null,
    docsUrl: value.docsUrl ?? null,
    setupGuide: value.setupGuide as string[],
    credentialFields: value.credentialFields.map((field) => parseProviderField(field, response)),
    unavailableReason: value.unavailableReason ?? null,
    source: value.source,
    enabled: value.enabled,
    defaultModel: value.defaultModel,
    usageCount: Number(value.usageCount),
    actions: value.actions as ModelCatalogAction[],
  }
}

function parseProviderField(value: unknown, response: AxiosResponse): ModelProviderField {
  if (!isRecord(value) || typeof value.key !== 'string' || !value.key || typeof value.label !== 'string' || !value.label
    || !providerFieldTypes.includes(value.type as ModelProviderFieldType) || typeof value.required !== 'boolean'
    || typeof value.secret !== 'boolean' || !Array.isArray(value.options)) {
    throw new ApiProtocolError('模型模板字段格式错误', value, response.config)
  }
  return {
    key: value.key,
    label: value.label,
    type: value.type as ModelProviderFieldType,
    required: value.required,
    secret: value.secret,
    options: value.options,
    defaultValue: value.defaultValue,
  }
}

function parseProviderTemplate(value: unknown, response: AxiosResponse): ModelProviderTemplate {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id || typeof value.providerCode !== 'string'
    || !value.providerCode || typeof value.name !== 'string' || !value.name
    || (value.sort !== null && value.sort !== undefined && (!Number.isSafeInteger(value.sort) || Number(value.sort) < 0))
    || !Array.isArray(value.fields)) {
    throw new ApiProtocolError('模型模板数据格式错误', value, response.config)
  }
  return {
    id: value.id,
    modelType: parseModelType(value.modelType, response),
    providerCode: value.providerCode,
    name: value.name,
    sort: value.sort === null || value.sort === undefined ? null : Number(value.sort),
    fields: value.fields.map((field) => parseProviderField(field, response)),
  }
}

function parseTest(value: unknown, response: AxiosResponse): ModelTestResult {
  if (!isRecord(value) || typeof value.success !== 'boolean' || typeof value.message !== 'string'
    || typeof value.elapsedMillis !== 'number' || !Number.isSafeInteger(value.elapsedMillis) || value.elapsedMillis < 0) {
    throw new ApiProtocolError('模型连接测试数据格式错误', value, response.config)
  }
  return { success: value.success, message: value.message, elapsedMillis: value.elapsedMillis }
}

function payload(input: ModelEditorInput) {
  const result: Record<string, unknown> = {
    modelType: input.modelType,
    name: input.name,
    providerCode: input.providerCode,
    vendorName: input.vendorName,
    protocol: input.protocol,
    credentialRequired: input.credentialRequired,
    providerTemplateId: input.providerTemplateId || undefined,
    apiUrl: input.apiUrl || undefined,
    modelId: input.modelId || undefined,
    config: input.config,
    secrets: input.secrets,
    clearSecretKeys: input.clearSecretKeys,
    enabled: input.enabled ? 1 : 0,
  }
  if (input.apiKey?.trim()) result.apiKey = input.apiKey.trim()
  return result
}

function requestConfig(options?: RequestOptions) { return options?.signal ? { signal: options.signal } : undefined }
function encoded(id: string) { return encodeURIComponent(id) }

export async function listModelCatalog(modelType: ModelType, options?: RequestOptions, view: 'management' | 'selection' = 'management') {
  const response = await http.get<ApiResult<unknown>>('/companion/models/catalog', {
    params: { modelType, view }, ...requestConfig(options),
  })
  const data = unwrap(response)
  if (!Array.isArray(data)) throw new ApiProtocolError('模型目录列表格式错误', data, response.config)
  return data.map((item) => parseCatalogItem(item, response))
}

export async function listModelTemplates(modelType: ModelType, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/companion/models/templates', {
    params: { modelType }, ...requestConfig(options),
  })
  const data = unwrap(response)
  if (!Array.isArray(data)) throw new ApiProtocolError('模型模板列表格式错误', data, response.config)
  return data.map((item) => parseProviderTemplate(item, response))
}

export async function copyModel(reference: string, name?: string, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>('/companion/models/copy',
    { reference, name: name || undefined }, requestConfig(options))
  return parseModel(unwrap(response), response)
}

export async function listPrivateModels(modelType: ModelType, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/companion/models', { params: { modelType }, ...requestConfig(options) })
  const data = unwrap(response)
  if (!Array.isArray(data)) throw new ApiProtocolError('私有模型列表数据格式错误', data, response.config)
  return data.map((item) => parseModel(item, response))
}

export async function getPrivateModel(id: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/companion/models/${encoded(id)}`, requestConfig(options))
  return parseModel(unwrap(response), response)
}

export async function createPrivateModel(input: ModelEditorInput, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>('/companion/models', payload(input), requestConfig(options))
  return parseModel(unwrap(response), response)
}

export async function updatePrivateModel(id: string, input: ModelEditorInput, options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(`/companion/models/${encoded(id)}`, payload(input), requestConfig(options))
  return parseModel(unwrap(response), response)
}

export async function deletePrivateModel(id: string, options?: RequestOptions) {
  const response = await http.delete<ApiResult<unknown>>(`/companion/models/${encoded(id)}`, requestConfig(options))
  unwrap(response)
}

export async function testPrivateModel(id: string | null, input: ModelEditorInput, options?: RequestOptions) {
  const url = id ? `/companion/models/${encoded(id)}/test` : '/companion/models/test'
  const response = await http.post<ApiResult<unknown>>(url, payload(input), requestConfig(options))
  return parseTest(unwrap(response), response)
}

function parseGlobalCredential(value: unknown, response: AxiosResponse): GlobalModelCredential {
  if (!isRecord(value) || Object.keys(value).some((key) => ['secrets', 'apiKey', 'api_key', 'secretConfigCiphertext'].includes(key))
    || typeof value.globalModelId !== 'string' || !value.globalModelId || !optionalString(value.apiUrl)
    || !optionalString(value.modelId) || !credentialRequirements.includes(value.credentialRequirement as CredentialRequirement)
    || typeof value.credentialConfigured !== 'boolean'
    || !credentialStatuses.includes(value.credentialStatus as CredentialStatus)) {
    throw new ApiProtocolError('系统模型配置数据格式错误', value, response.config)
  }
  return {
    globalModelId: value.globalModelId,
    apiUrl: value.apiUrl ?? null,
    modelId: value.modelId ?? null,
    configuredSecretKeys: parseSecretKeys(value.configuredSecretKeys, response),
    credentialRequirement: value.credentialRequirement as CredentialRequirement,
    credentialConfigured: value.credentialConfigured,
    credentialStatus: value.credentialStatus as CredentialStatus,
  }
}

export async function getGlobalModelConfig(id: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/companion/models/global/${encoded(id)}/config`, requestConfig(options))
  return parseGlobalCredential(unwrap(response), response)
}

export async function saveGlobalModelConfig(id: string, input: GlobalModelCredentialInput, options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(`/companion/models/global/${encoded(id)}/config`, input, requestConfig(options))
  return parseGlobalCredential(unwrap(response), response)
}

export async function testGlobalModelConfig(id: string, input: GlobalModelCredentialInput, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(`/companion/models/global/${encoded(id)}/test`, input, requestConfig(options))
  return parseTest(unwrap(response), response)
}
