import type { AxiosResponse } from 'axios'

import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'

export const modelTypes = ['LLM', 'VLLM', 'TTS', 'ASR', 'VAD', 'Memory', 'Embedding'] as const
export type ModelType = typeof modelTypes[number]
export const modelProviderFieldTypes = ['string', 'password', 'number', 'integer', 'int', 'float', 'boolean', 'dict'] as const
export type ModelProviderFieldType = typeof modelProviderFieldTypes[number]

export interface ModelConfig {
  id: string
  modelType: ModelType
  modelCode: string
  modelName: string
  isDefault: 0 | 1
  isEnabled: 0 | 1
  configJson: Record<string, unknown> | null
  docLink: string | null
  remark: string | null
  sort: number | null
  configuredSecretPaths: string[]
}

export interface CreateModelConfigInput {
  id?: string
  modelCode?: string
  modelName?: string
  isEnabled?: 0 | 1
  configJson?: Record<string, unknown> | null
  docLink?: string | null
  remark?: string | null
  sort?: number | null
}

export interface UpdateModelConfigInput {
  modelName?: string
  isEnabled?: 0 | 1
  configJson?: Record<string, unknown> | null
  remark?: string | null
  sort?: number | null
}

export interface ModelBasicInfo {
  id: string
  modelName: string
}

export interface ModelProviderField {
  key: string
  label: string
  type: ModelProviderFieldType
  options?: unknown[]
  default?: unknown
  dict_name?: string
}

export interface ModelProvider {
  id: string
  modelType: ModelType
  providerCode: string
  name: string
  fields: ModelProviderField[]
  sort: number
  updater: string | null
  updateDate: string | null
  creator: string | null
  createDate: string | null
}

export interface ModelVoice {
  id: string
  name: string
  voiceDemo: string | null
  languages: string | null
  isClone: boolean
}

export interface ModelConfigPage {
  total: number
  list: ModelConfig[]
}

export interface ModelTestResult {
  success: boolean
  elapsedMillis: number
  message: string
}

export interface ModelConfigListParams {
  modelType: ModelType
  modelName?: string
  page: number
  limit: number
}

interface RequestOptions { signal?: AbortSignal }

const sensitiveKeyFragments = [
  'api_key',
  'api_secret',
  'secret',
  'secret_id',
  'secret_key',
  'password',
  'api_password',
  'authorization',
  'private_key',
  'access_key_secret',
  'credential',
] as const

interface RedactedValue {
  value: unknown
  paths: string[]
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

function normalizeKey(key: string) {
  return key
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^a-zA-Z0-9]+/g, '_')
    .toLowerCase()
}

function isSensitiveKey(key: string) {
  const normalized = normalizeKey(key)
  return /(^|_)token($|_)/.test(normalized)
    || sensitiveKeyFragments.some((fragment) => normalized.includes(fragment))
}

function isConfiguredSecret(value: unknown) {
  if (typeof value === 'string') return Boolean(value.trim())
  if (value === null || value === undefined) return false
  if (Array.isArray(value)) return value.length > 0
  if (isRecord(value)) return Object.keys(value).length > 0
  return true
}

function redactSensitive(value: unknown): RedactedValue {
  const paths: string[] = []

  function visit(current: unknown, path: string): unknown {
    if (Array.isArray(current)) {
      return current.map((item, index) => visit(item, `${path}[${index}]`))
    }
    if (!isRecord(current)) return current

    const result: Record<string, unknown> = {}
    const declaresSensitiveField = typeof current.key === 'string' && isSensitiveKey(current.key)
    for (const [key, item] of Object.entries(current)) {
      const itemPath = path ? `${path}.${key}` : key
      if (isSensitiveKey(key) || (declaresSensitiveField && ['default', 'options', 'value'].includes(key))) {
        if (isConfiguredSecret(item)) paths.push(itemPath)
        continue
      }
      result[key] = visit(item, itemPath)
    }
    return result
  }

  return { value: visit(value, ''), paths }
}

function protocolError(message: string, data: unknown, response: AxiosResponse) {
  return new ApiProtocolError(message, redactSensitive(data).value, response.config)
}

function optionalString(value: unknown): value is string | null | undefined {
  return value === null || value === undefined || typeof value === 'string'
}

function nullableLongString(value: unknown): value is string | null | undefined {
  return value === null || value === undefined || (typeof value === 'string' && /^\d+$/.test(value))
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function parseModelType(value: unknown, response: AxiosResponse) {
  if (!modelTypes.includes(value as ModelType)) {
    throw protocolError('模型类型字段错误', value, response)
  }
  return value as ModelType
}

function unwrap(response: AxiosResponse<ApiResult<unknown>>) {
  const result: unknown = response.data
  if (!isRecord(result) || typeof result.code !== 'number' || typeof result.msg !== 'string' || !('data' in result)) {
    throw protocolError('响应数据格式错误', result, response)
  }
  if (result.code !== 0) {
    throw new ApiError(result.code, result.msg || '请求失败', redactSensitive(result.data).value, response.config)
  }
  return result.data
}

function parseModel(value: unknown, response: AxiosResponse): ModelConfig {
  const redacted = redactSensitive(value)
  const safeValue = redacted.value
  if (!isRecord(safeValue) || typeof safeValue.id !== 'string' || !safeValue.id
    || typeof safeValue.modelCode !== 'string' || !safeValue.modelCode
    || typeof safeValue.modelName !== 'string' || !safeValue.modelName
    || (safeValue.isDefault !== 0 && safeValue.isDefault !== 1)
    || (safeValue.isEnabled !== 0 && safeValue.isEnabled !== 1)
    || (safeValue.configJson !== null && !isRecord(safeValue.configJson))
    || !optionalString(safeValue.docLink) || !optionalString(safeValue.remark)
    || (safeValue.sort !== null && !isNonNegativeInteger(safeValue.sort))) {
    throw protocolError('模型配置数据字段错误', safeValue, response)
  }
  return {
    id: safeValue.id,
    modelType: parseModelType(safeValue.modelType, response),
    modelCode: safeValue.modelCode,
    modelName: safeValue.modelName,
    isDefault: safeValue.isDefault,
    isEnabled: safeValue.isEnabled,
    configJson: safeValue.configJson,
    docLink: safeValue.docLink ?? null,
    remark: safeValue.remark ?? null,
    sort: safeValue.sort,
    configuredSecretPaths: redacted.paths
      .filter((path) => path.startsWith('configJson.'))
      .map((path) => path.slice('configJson.'.length)),
  }
}

function parseModelName(value: unknown, response: AxiosResponse): ModelBasicInfo {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id
    || typeof value.modelName !== 'string' || !value.modelName) {
    throw protocolError('模型名称数据字段错误', value, response)
  }
  return { id: value.id, modelName: value.modelName }
}

function parseProviderFields(value: unknown, response: AxiosResponse): ModelProviderField[] {
  if (typeof value !== 'string') {
    throw protocolError('模型供应器字段格式错误', value, response)
  }
  let fields: unknown
  try {
    fields = JSON.parse(value)
  } catch {
    throw protocolError('模型供应器字段格式错误', null, response)
  }
  if (!Array.isArray(fields)) {
    throw protocolError('模型供应器字段格式错误', fields, response)
  }
  return fields.map((field) => {
    if (!isRecord(field) || typeof field.key !== 'string' || !field.key
      || typeof field.label !== 'string' || !field.label
      || !modelProviderFieldTypes.includes(field.type as ModelProviderFieldType)
      || ('options' in field && !Array.isArray(field.options))
      || ('dict_name' in field && (typeof field.dict_name !== 'string' || !field.dict_name))) {
      throw protocolError('模型供应器字段格式错误', field, response)
    }
    const result: ModelProviderField = {
      key: field.key,
      label: field.label,
      type: field.type as ModelProviderFieldType,
    }
    if (!isSensitiveKey(field.key) && 'options' in field) {
      result.options = redactSensitive(field.options).value as unknown[]
    }
    if (!isSensitiveKey(field.key) && 'default' in field) {
      result.default = redactSensitive(field.default).value
    }
    if ('dict_name' in field) result.dict_name = field.dict_name as string
    return result
  })
}

function parseProvider(value: unknown, response: AxiosResponse): ModelProvider {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id
    || typeof value.providerCode !== 'string' || !value.providerCode
    || typeof value.name !== 'string' || !value.name || !isNonNegativeInteger(value.sort)
    || !nullableLongString(value.updater) || !nullableLongString(value.creator)
    || !optionalString(value.updateDate) || !optionalString(value.createDate)) {
    throw protocolError('模型供应器数据字段错误', value, response)
  }
  return {
    id: value.id,
    modelType: parseModelType(value.modelType, response),
    providerCode: value.providerCode,
    name: value.name,
    fields: parseProviderFields(value.fields, response),
    sort: value.sort,
    updater: value.updater ?? null,
    updateDate: value.updateDate ?? null,
    creator: value.creator ?? null,
    createDate: value.createDate ?? null,
  }
}

function parseVoice(value: unknown, response: AxiosResponse): ModelVoice {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id
    || typeof value.name !== 'string' || !value.name
    || !optionalString(value.voiceDemo) || !optionalString(value.languages)
    || typeof value.isClone !== 'boolean') {
    throw protocolError('模型音色数据字段错误', value, response)
  }
  return {
    id: value.id,
    name: value.name,
    voiceDemo: value.voiceDemo ?? null,
    languages: value.languages ?? null,
    isClone: value.isClone,
  }
}

function parseModelTest(value: unknown, response: AxiosResponse): ModelTestResult {
  if (!isRecord(value) || typeof value.success !== 'boolean' || typeof value.message !== 'string'
    || !isNonNegativeInteger(value.elapsedMillis)) {
    throw protocolError('模型连接测试数据格式错误', value, response)
  }
  return {
    success: value.success,
    elapsedMillis: value.elapsedMillis,
    message: value.message,
  }
}

function requestConfig(options?: RequestOptions) {
  return options?.signal ? { signal: options.signal } : undefined
}

function encoded(value: string) {
  return encodeURIComponent(value)
}

function createPayload(input: CreateModelConfigInput): CreateModelConfigInput {
  const result: CreateModelConfigInput = {}
  if (input.id !== undefined) result.id = input.id
  if (input.modelCode !== undefined) result.modelCode = input.modelCode
  if (input.modelName !== undefined) result.modelName = input.modelName
  if (input.isEnabled !== undefined) result.isEnabled = input.isEnabled
  if (input.configJson !== undefined) result.configJson = input.configJson
  if (input.docLink !== undefined) result.docLink = input.docLink
  if (input.remark !== undefined) result.remark = input.remark
  if (input.sort !== undefined) result.sort = input.sort
  return result
}

function updatePayload(input: UpdateModelConfigInput): UpdateModelConfigInput {
  const result: UpdateModelConfigInput = {}
  if (input.modelName !== undefined) result.modelName = input.modelName
  if (input.isEnabled !== undefined) result.isEnabled = input.isEnabled
  if (input.configJson !== undefined) result.configJson = input.configJson
  if (input.remark !== undefined) result.remark = input.remark
  if (input.sort !== undefined) result.sort = input.sort
  return result
}

export async function listModelConfigs(params: ModelConfigListParams, options?: RequestOptions): Promise<ModelConfigPage> {
  if (!Number.isSafeInteger(params.page) || params.page <= 0) throw new RangeError('page 必须是大于 0 的安全整数')
  if (!Number.isSafeInteger(params.limit) || params.limit <= 0) throw new RangeError('limit 必须是大于 0 的安全整数')
  const response = await http.get<ApiResult<unknown>>('/models/list', {
    params: {
      modelType: params.modelType,
      modelName: params.modelName,
      page: params.page,
      limit: params.limit,
    },
    ...requestConfig(options),
  })
  const data = unwrap(response)
  if (!isRecord(data) || !isNonNegativeInteger(data.total) || !Array.isArray(data.list)) {
    throw protocolError('模型配置分页数据格式错误', data, response)
  }
  return { total: data.total, list: data.list.map((item) => parseModel(item, response)) }
}

export async function listModelNames(modelType: ModelType, modelName?: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/models/names', {
    params: { modelType, modelName },
    ...requestConfig(options),
  })
  const data = unwrap(response)
  if (!Array.isArray(data)) throw protocolError('模型名称列表格式错误', data, response)
  return data.map((item) => parseModelName(item, response))
}

export async function listModelVoices(modelId: string, voiceName?: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/models/${encoded(modelId)}/voices`, voiceName === undefined && !options
    ? undefined
    : { params: { voiceName }, ...requestConfig(options) })
  const data = unwrap(response)
  if (data === null) return []
  if (!Array.isArray(data)) throw protocolError('模型音色列表格式错误', data, response)
  return data.map((item) => parseVoice(item, response))
}

export async function getModelConfig(id: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/models/${encoded(id)}`, requestConfig(options))
  return parseModel(unwrap(response), response)
}

export async function listProviderTypes(modelType: ModelType, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/models/${encoded(modelType)}/provideTypes`, requestConfig(options))
  const data = unwrap(response)
  if (!Array.isArray(data)) throw protocolError('模型供应器列表格式错误', data, response)
  return data.map((item) => parseProvider(item, response))
}

export async function createModelConfig(modelType: ModelType, providerCode: string, input: CreateModelConfigInput, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(
    `/models/${encoded(modelType)}/${encoded(providerCode)}`,
    createPayload(input),
    requestConfig(options),
  )
  return parseModel(unwrap(response), response)
}

export async function updateModelConfig(modelType: ModelType, providerCode: string, id: string, input: UpdateModelConfigInput, options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(
    `/models/${encoded(modelType)}/${encoded(providerCode)}/${encoded(id)}`,
    updatePayload(input),
    requestConfig(options),
  )
  return parseModel(unwrap(response), response)
}

export async function testModelConfig(
  modelType: ModelType,
  providerCode: string,
  id: string | null,
  input: CreateModelConfigInput,
  options?: RequestOptions,
) {
  const base = `/models/${encoded(modelType)}/${encoded(providerCode)}`
  const url = id ? `${base}/${encoded(id)}/test` : `${base}/test`
  const response = await http.post<ApiResult<unknown>>(url, createPayload(input), requestConfig(options))
  return parseModelTest(unwrap(response), response)
}

export async function setModelEnabled(id: string, enabled: boolean, options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(
    `/models/enable/${encoded(id)}/${enabled ? 1 : 0}`,
    undefined,
    requestConfig(options),
  )
  unwrap(response)
}

export async function setDefaultModel(id: string, options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(`/models/default/${encoded(id)}`, undefined, requestConfig(options))
  unwrap(response)
}

export async function deleteModelConfig(id: string, options?: RequestOptions) {
  const response = await http.delete<ApiResult<unknown>>(`/models/${encoded(id)}`, requestConfig(options))
  unwrap(response)
}
