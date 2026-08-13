import type { AxiosResponse } from 'axios'

import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'

export type VolcengineResourceId = 'seed-tts-1.0' | 'seed-tts-2.0'

export interface VolcengineVoice {
  id: string
  name: string
  voiceType: string
  gender: string | null
  age: string | null
  languages: string | null
  tags: string[]
  description: string | null
  trialUrl: string | null
}

export interface ListVolcengineVoicesParams {
  resourceId: VolcengineResourceId
  page: number
  limit: number
  name?: string
  voiceType?: string
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

function parseVoice(value: unknown, response: AxiosResponse): VolcengineVoice {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id
    || typeof value.name !== 'string' || !value.name
    || typeof value.voiceType !== 'string' || !value.voiceType
    || !optionalString(value.gender) || !optionalString(value.age) || !optionalString(value.languages)
    || !Array.isArray(value.tags) || value.tags.some((tag) => typeof tag !== 'string')
    || !optionalString(value.description) || !optionalString(value.trialUrl)) {
    throw new ApiProtocolError('火山音色数据格式错误', value, response.config)
  }
  return {
    id: value.id,
    name: value.name,
    voiceType: value.voiceType,
    gender: value.gender ?? null,
    age: value.age ?? null,
    languages: value.languages ?? null,
    tags: value.tags,
    description: value.description ?? null,
    trialUrl: value.trialUrl ?? null,
  }
}

export async function listVolcengineVoices(
  params: ListVolcengineVoicesParams,
  options?: RequestOptions,
): Promise<{ total: number; list: VolcengineVoice[] }> {
  if (!['seed-tts-1.0', 'seed-tts-2.0'].includes(params.resourceId)) throw new TypeError('模型版本无效')
  if (!nonNegativeInteger(params.page) || params.page < 1) throw new RangeError('page 必须是正整数')
  if (!nonNegativeInteger(params.limit) || params.limit < 1) throw new RangeError('limit 必须是正整数')
  const response = await http.get<ApiResult<unknown>>('/volcengine/voices', {
    params,
    ...requestConfig(options),
  })
  const data = unwrap(response)
  if (!isRecord(data) || !nonNegativeInteger(data.total) || !Array.isArray(data.list)) {
    throw new ApiProtocolError('火山音色分页数据格式错误', data, response.config)
  }
  return { total: data.total, list: data.list.map((item) => parseVoice(item, response)) }
}
