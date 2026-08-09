import type { AxiosResponse } from 'axios'

import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'

export interface Timbre {
  id: string
  ttsModelId: string
  name: string
  ttsVoice: string
  languages: string | null
  voiceDemo: string | null
  remark: string | null
  sort: number | null
}

export interface TimbreInput {
  ttsModelId: string
  ttsVoice: string
  name: string
  languages: string
  sort: number
  voiceDemo?: string
  remark?: string
  referenceAudio?: string
  referenceText?: string
}

export interface TimbrePage {
  total: number
  list: Timbre[]
}

export interface TimbreListParams {
  ttsModelId: string
  page: number
  limit: number
  name: string
}

interface RequestOptions { signal?: AbortSignal }

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

function optionalString(value: unknown): value is string | null | undefined {
  return value === null || value === undefined || typeof value === 'string'
}

function nonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function nullableNonNegativeInteger(value: unknown): number | null | undefined {
  if (value === null) return null
  if (nonNegativeInteger(value)) return value
  if (typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value)) {
    const parsed = Number(value)
    if (Number.isSafeInteger(parsed)) return parsed
  }
  return undefined
}

function requestConfig(options?: RequestOptions) {
  return options?.signal ? { signal: options.signal } : undefined
}

function unwrap(response: AxiosResponse<ApiResult<unknown>>) {
  const result: unknown = response.data
  if (!isRecord(result) || typeof result.code !== 'number' || typeof result.msg !== 'string' || !('data' in result)) {
    throw new ApiProtocolError('响应数据格式错误', result, response.config)
  }
  if (result.code !== 0) throw new ApiError(result.code, result.msg || '请求失败', result.data, response.config)
  return result.data
}

function parseTimbre(value: unknown, response: AxiosResponse): Timbre {
  const sort = isRecord(value) ? nullableNonNegativeInteger(value.sort) : undefined
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id
    || typeof value.ttsModelId !== 'string' || !value.ttsModelId
    || typeof value.name !== 'string' || !value.name
    || typeof value.ttsVoice !== 'string' || !value.ttsVoice
    || !optionalString(value.languages) || !optionalString(value.voiceDemo) || !optionalString(value.remark)
    || sort === undefined) {
    throw new ApiProtocolError('音色数据字段错误', value, response.config)
  }
  return {
    id: value.id,
    ttsModelId: value.ttsModelId,
    name: value.name,
    ttsVoice: value.ttsVoice,
    languages: value.languages ?? null,
    voiceDemo: value.voiceDemo ?? null,
    remark: value.remark ?? null,
    sort,
  }
}

function payload(input: TimbreInput): TimbreInput {
  for (const [field, value] of Object.entries({
    ttsModelId: input.ttsModelId,
    ttsVoice: input.ttsVoice,
    name: input.name,
    languages: input.languages,
  })) {
    if (typeof value !== 'string' || !value.trim()) throw new TypeError(`${field} 不能为空`)
  }
  if (!nonNegativeInteger(input.sort)) throw new RangeError('sort 必须是非负整数')
  const result: TimbreInput = {
    ttsModelId: input.ttsModelId,
    ttsVoice: input.ttsVoice,
    name: input.name,
    languages: input.languages,
    sort: input.sort,
  }
  for (const field of ['voiceDemo', 'remark', 'referenceAudio', 'referenceText'] as const) {
    const value = input[field]
    if (value !== undefined) result[field] = value
  }
  return result
}

export async function listTimbres(params: TimbreListParams, options?: RequestOptions): Promise<TimbrePage> {
  if (!params.ttsModelId.trim()) throw new TypeError('ttsModelId 不能为空')
  if (!nonNegativeInteger(params.page) || params.page < 1) throw new RangeError('page 必须是正整数')
  if (!nonNegativeInteger(params.limit) || params.limit < 1) throw new RangeError('limit 必须是正整数')
  const response = await http.get<ApiResult<unknown>>('/ttsVoice', { params, ...requestConfig(options) })
  const data = unwrap(response)
  if (!isRecord(data) || !nonNegativeInteger(data.total) || !Array.isArray(data.list)) {
    throw new ApiProtocolError('音色分页数据格式错误', data, response.config)
  }
  return { total: data.total, list: data.list.map((item) => parseTimbre(item, response)) }
}

export async function createTimbre(input: TimbreInput, options?: RequestOptions) {
  unwrap(await http.post<ApiResult<unknown>>('/ttsVoice', payload(input), requestConfig(options)))
}

export async function updateTimbre(id: string, input: TimbreInput, options?: RequestOptions) {
  unwrap(await http.put<ApiResult<unknown>>(`/ttsVoice/${encodeURIComponent(id)}`, payload(input), requestConfig(options)))
}

export async function deleteTimbres(ids: string[], options?: RequestOptions) {
  if (!Array.isArray(ids) || ids.length === 0 || ids.some((id) => typeof id !== 'string' || !id)) {
    throw new TypeError('音色 ID 不能为空')
  }
  unwrap(await http.post<ApiResult<unknown>>('/ttsVoice/delete', ids, requestConfig(options)))
}
