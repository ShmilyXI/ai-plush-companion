import type { AxiosResponse } from 'axios'

import http, { type ApiResult } from './http'
import { isRecord, parseModelTest, protocolError, unwrap, type ModelTestResult } from './zixuanModels'

/** 连接目前只管理对话类模型。 */
export const channelModelTypes = ['LLM', 'VLLM', 'Embedding'] as const
export type ChannelModelType = typeof channelModelTypes[number]
export const channelProviderCodes = ['openai', 'ollama'] as const
export type ChannelProviderCode = typeof channelProviderCodes[number]

export interface ModelChannel {
  id: string
  name: string
  vendorCode: string | null
  providerCode: ChannelProviderCode
  baseUrl: string
  /** 服务端只告知是否保存了密钥，从不返回密钥内容。 */
  credentialConfigured: boolean
  modelCount: number
  remark: string | null
  sort: number
}

export interface ModelChannelInput {
  name?: string
  vendorCode?: string | null
  providerCode?: ChannelProviderCode
  baseUrl?: string
  /** 留空表示保留已保存的密钥。 */
  apiKey?: string
  clearApiKey?: boolean
  remark?: string | null
}

export type ChannelModelParamValue = string | number | boolean

export interface ChannelModel {
  id: string
  channelId: string
  modelType: ChannelModelType
  /** 服务商给出的模型代号。 */
  modelId: string
  /** 显示名称。 */
  modelName: string
  isEnabled: boolean
  isDefault: boolean
  params: Record<string, ChannelModelParamValue>
}

export interface UpstreamModel {
  id: string
  addedTypes: ChannelModelType[]
}

export interface AddChannelModelInput {
  modelType: ChannelModelType
  modelId: string
  modelName?: string
}

export interface UpdateChannelModelInput {
  modelName: string
  /** 值为 null 表示删除该参数。 */
  params?: Record<string, ChannelModelParamValue | null>
}

interface RequestOptions { signal?: AbortSignal }

function requestConfig(options?: RequestOptions) {
  return options?.signal ? { signal: options.signal } : undefined
}

function encoded(value: string) {
  return encodeURIComponent(value)
}

function optionalString(value: unknown): value is string | null | undefined {
  return value === null || value === undefined || typeof value === 'string'
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function isModelType(value: unknown): value is ChannelModelType {
  return channelModelTypes.includes(value as ChannelModelType)
}

function parseChannel(value: unknown, response: AxiosResponse): ModelChannel {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id
    || typeof value.name !== 'string' || !value.name
    || !channelProviderCodes.includes(value.providerCode as ChannelProviderCode)
    || typeof value.baseUrl !== 'string' || !value.baseUrl
    || typeof value.credentialConfigured !== 'boolean'
    || !isCount(value.modelCount)
    || !optionalString(value.vendorCode) || !optionalString(value.remark)
    || (value.sort !== null && value.sort !== undefined && !isCount(value.sort))) {
    throw protocolError('模型连接数据字段错误', value, response)
  }
  return {
    id: value.id,
    name: value.name,
    vendorCode: value.vendorCode ?? null,
    providerCode: value.providerCode as ChannelProviderCode,
    baseUrl: value.baseUrl,
    credentialConfigured: value.credentialConfigured,
    modelCount: value.modelCount,
    remark: value.remark ?? null,
    sort: value.sort ?? 0,
  }
}

function parseParams(value: unknown, response: AxiosResponse) {
  if (value === null || value === undefined) return {}
  if (!isRecord(value)) throw protocolError('模型参数格式错误', value, response)
  const params: Record<string, ChannelModelParamValue> = {}
  for (const [key, item] of Object.entries(value)) {
    if (typeof item === 'string' || typeof item === 'number' || typeof item === 'boolean') params[key] = item
  }
  return params
}

function parseChannelModel(value: unknown, response: AxiosResponse): ChannelModel {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id
    || typeof value.channelId !== 'string' || !value.channelId
    || !isModelType(value.modelType)
    || !optionalString(value.modelId)
    || typeof value.modelName !== 'string' || !value.modelName
    || (value.isEnabled !== 0 && value.isEnabled !== 1)
    || (value.isDefault !== 0 && value.isDefault !== 1)) {
    throw protocolError('连接模型数据字段错误', value, response)
  }
  return {
    id: value.id,
    channelId: value.channelId,
    modelType: value.modelType,
    modelId: value.modelId ?? '',
    modelName: value.modelName,
    isEnabled: value.isEnabled === 1,
    isDefault: value.isDefault === 1,
    params: parseParams(value.params, response),
  }
}

function parseUpstreamModel(value: unknown, response: AxiosResponse): UpstreamModel {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id
    || !Array.isArray(value.addedTypes) || !value.addedTypes.every(isModelType)) {
    throw protocolError('上游模型数据字段错误', value, response)
  }
  return { id: value.id, addedTypes: value.addedTypes }
}

function parseList<T>(
  data: unknown,
  response: AxiosResponse,
  parse: (value: unknown, response: AxiosResponse) => T,
  message: string,
) {
  if (!Array.isArray(data)) throw protocolError(message, data, response)
  return data.map((item) => parse(item, response))
}

function channelPayload(input: ModelChannelInput) {
  const payload: Record<string, unknown> = {}
  if (input.name !== undefined) payload.name = input.name
  if (input.vendorCode !== undefined) payload.vendorCode = input.vendorCode
  if (input.providerCode !== undefined) payload.providerCode = input.providerCode
  if (input.baseUrl !== undefined) payload.baseUrl = input.baseUrl
  if (input.apiKey) payload.apiKey = input.apiKey
  if (input.clearApiKey) payload.clearApiKey = true
  if (input.remark !== undefined) payload.remark = input.remark
  return payload
}

export async function listModelChannels(options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/model-channels', requestConfig(options))
  return parseList(unwrap(response), response, parseChannel, '模型连接列表格式错误')
}

export async function createModelChannel(input: ModelChannelInput, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>('/model-channels', channelPayload(input), requestConfig(options))
  return parseChannel(unwrap(response), response)
}

export async function updateModelChannel(id: string, input: ModelChannelInput, options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(
    `/model-channels/${encoded(id)}`,
    channelPayload(input),
    requestConfig(options),
  )
  return parseChannel(unwrap(response), response)
}

export async function deleteModelChannel(id: string, options?: RequestOptions) {
  const response = await http.delete<ApiResult<unknown>>(`/model-channels/${encoded(id)}`, requestConfig(options))
  unwrap(response)
}

/** id 为 null 时测试未保存的连接；否则用提交的值覆盖已保存的值后测试。 */
export async function testModelChannel(
  id: string | null,
  input: ModelChannelInput,
  options?: RequestOptions,
): Promise<ModelTestResult> {
  const url = id ? `/model-channels/${encoded(id)}/test` : '/model-channels/test'
  const response = await http.post<ApiResult<unknown>>(url, channelPayload(input), requestConfig(options))
  return parseModelTest(unwrap(response), response)
}

export async function listUpstreamModels(channelId: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(
    `/model-channels/${encoded(channelId)}/upstream-models`,
    requestConfig(options),
  )
  return parseList(unwrap(response), response, parseUpstreamModel, '上游模型列表格式错误')
}

export async function listChannelModels(options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/model-channels/models', requestConfig(options))
  return parseList(unwrap(response), response, parseChannelModel, '连接模型列表格式错误')
}

export async function addChannelModels(channelId: string, models: AddChannelModelInput[], options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(
    `/model-channels/${encoded(channelId)}/models`,
    { models },
    requestConfig(options),
  )
  return parseList(unwrap(response), response, parseChannelModel, '连接模型列表格式错误')
}

export async function updateChannelModel(modelId: string, input: UpdateChannelModelInput, options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(
    `/model-channels/models/${encoded(modelId)}`,
    input,
    requestConfig(options),
  )
  return parseChannelModel(unwrap(response), response)
}
