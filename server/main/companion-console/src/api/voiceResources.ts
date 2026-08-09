import type { AxiosResponse } from 'axios'

import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'

export interface VoiceResource {
  id: string
  name: string
  modelId: string
  modelName: string
  voiceId: string
  languages: string
  userId: string
  userName: string
  trainStatus: 0 | 1 | 2 | 3
  trainError: string | null
  createDate: string | null
  hasVoice: boolean
}
export type VoiceResourceDetail = Omit<VoiceResource, 'hasVoice'> & { hasVoice: boolean | null }

export interface VoiceResourcePage { total: number; list: VoiceResource[] }
export interface VoiceResourceListParams {
  page: number
  limit: number
  name: string
  orderField?: 'create_date'
  order?: 'asc' | 'desc'
}
export interface VoiceResourceInput {
  modelId: string
  voiceIds: string[]
  userId: string
  languages: string
}
export interface TtsPlatform { id: string; modelName: string }
export interface AssignableUser { id: string; mobile: string }
export interface AssignableUserPage { total: number; list: AssignableUser[] }
interface RequestOptions { signal?: AbortSignal }

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

function config(options?: RequestOptions) {
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

function positiveLongString(value: unknown, response?: AxiosResponse): string {
  if (typeof value === 'string' && /^[1-9]\d*$/.test(value)) return value
  if (!response) throw new TypeError('用户 ID 格式错误')
  throw new ApiProtocolError('用户 ID 格式错误', value, response?.config)
}

function nonNegativeInteger(value: unknown, response?: AxiosResponse): number {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return value
  if (typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value)) {
    const parsed = Number(value)
    if (Number.isSafeInteger(parsed)) return parsed
  }
  throw new ApiProtocolError('分页总数格式错误', value, response?.config)
}

function nonBlank(value: unknown): value is string {
  return typeof value === 'string' && Boolean(value.trim())
}

export function parseVoiceResource(value: unknown, response: AxiosResponse): VoiceResource {
  if (!isRecord(value) || !nonBlank(value.id) || !nonBlank(value.name) || !nonBlank(value.modelId)
    || !nonBlank(value.modelName) || !nonBlank(value.voiceId) || typeof value.languages !== 'string'
    || typeof value.userName !== 'string' || ![0, 1, 2, 3].includes(value.trainStatus as number)
    || (value.trainError !== null && typeof value.trainError !== 'string')
    || (value.createDate !== null && typeof value.createDate !== 'string') || typeof value.hasVoice !== 'boolean') {
    throw new ApiProtocolError('音色资源字段错误', value, response.config)
  }
  return {
    id: value.id,
    name: value.name,
    modelId: value.modelId,
    modelName: value.modelName,
    voiceId: value.voiceId,
    languages: value.languages,
    userId: positiveLongString(value.userId, response),
    userName: value.userName,
    trainStatus: value.trainStatus as VoiceResource['trainStatus'],
    trainError: value.trainError,
    createDate: value.createDate,
    hasVoice: value.hasVoice,
  }
}

function parseVoiceResourceDetail(value: unknown, response: AxiosResponse): VoiceResourceDetail {
  if (isRecord(value) && value.hasVoice === null) {
    return { ...parseVoiceResource({ ...value, hasVoice: false }, response), hasVoice: null }
  }
  return parseVoiceResource(value, response)
}

export function parseVoiceResourcePage(value: unknown, response: AxiosResponse): VoiceResourcePage {
  if (!isRecord(value) || !Array.isArray(value.list)) {
    throw new ApiProtocolError('音色资源分页格式错误', value, response.config)
  }
  return { total: nonNegativeInteger(value.total, response), list: value.list.map((item) => parseVoiceResource(item, response)) }
}

function listParams(params: VoiceResourceListParams) {
  if (!Number.isSafeInteger(params.page) || params.page < 1) throw new RangeError('page 必须是正整数')
  if (!Number.isSafeInteger(params.limit) || params.limit < 1) throw new RangeError('limit 必须是正整数')
  if (typeof params.name !== 'string') throw new TypeError('name 必须是字符串')
  return {
    page: params.page,
    limit: params.limit,
    name: params.name,
    ...(params.orderField ? { orderField: params.orderField } : {}),
    ...(params.order ? { order: params.order } : {}),
  }
}

export async function listVoiceResources(params: VoiceResourceListParams, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/voiceResource', { params: listParams(params), ...config(options) })
  return parseVoiceResourcePage(unwrap(response), response)
}

export async function getVoiceResource(id: string, options?: RequestOptions) {
  if (!nonBlank(id)) throw new TypeError('音色资源 ID 不能为空')
  const response = await http.get<ApiResult<unknown>>(`/voiceResource/${encodeURIComponent(id)}`, config(options))
  return parseVoiceResourceDetail(unwrap(response), response)
}

export async function createVoiceResources(input: VoiceResourceInput, options?: RequestOptions) {
  if (!nonBlank(input.modelId) || !Array.isArray(input.voiceIds) || input.voiceIds.length === 0
    || input.voiceIds.some((id) => !nonBlank(id)) || !nonBlank(input.languages)) {
    throw new TypeError('音色资源信息不完整')
  }
  const body: VoiceResourceInput = {
    modelId: input.modelId,
    voiceIds: [...input.voiceIds],
    userId: positiveLongString(input.userId),
    languages: input.languages,
  }
  unwrap(await http.post<ApiResult<unknown>>('/voiceResource', body, config(options)))
}

export async function deleteVoiceResources(ids: string[], options?: RequestOptions) {
  if (!Array.isArray(ids) || ids.length === 0 || ids.some((id) => !nonBlank(id))) throw new TypeError('音色资源 ID 不能为空')
  const encoded = encodeURIComponent(ids.join(','))
  unwrap(await http.delete<ApiResult<unknown>>(`/voiceResource/${encoded}`, config(options)))
}

export async function listUserVoiceResources(userId: string, options?: RequestOptions) {
  const normalized = positiveLongString(userId)
  const response = await http.get<ApiResult<unknown>>(`/voiceResource/user/${normalized}`, config(options))
  const data = unwrap(response)
  if (!Array.isArray(data)) throw new ApiProtocolError('用户音色资源格式错误', data, response.config)
  return data.map((item) => parseVoiceResource(item, response))
}

export async function listTtsPlatforms(options?: RequestOptions): Promise<TtsPlatform[]> {
  const response = await http.get<ApiResult<unknown>>('/voiceResource/ttsPlatforms', config(options))
  const data = unwrap(response)
  if (!Array.isArray(data)) throw new ApiProtocolError('TTS 平台格式错误', data, response.config)
  return data.map((item) => {
    if (!isRecord(item) || !nonBlank(item.id) || !nonBlank(item.modelName)) {
      throw new ApiProtocolError('TTS 平台字段错误', item, response.config)
    }
    return { id: item.id, modelName: item.modelName }
  })
}

export async function listAssignableUsers(mobile: string, page = 1, limit = 20, options?: RequestOptions): Promise<AssignableUserPage> {
  if (typeof mobile !== 'string' || !Number.isSafeInteger(page) || page < 1 || !Number.isSafeInteger(limit) || limit < 1) {
    throw new TypeError('用户查询参数错误')
  }
  const response = await http.get<ApiResult<unknown>>('/admin/users', { params: { mobile, page, limit }, ...config(options) })
  const data = unwrap(response)
  if (!isRecord(data) || !Array.isArray(data.list)) throw new ApiProtocolError('用户分页格式错误', data, response.config)
  return {
    total: nonNegativeInteger(data.total, response),
    list: data.list.map((item) => {
      if (!isRecord(item) || typeof item.mobile !== 'string') throw new ApiProtocolError('用户字段错误', item, response.config)
      return { id: positiveLongString(item.userid, response), mobile: item.mobile }
    }),
  }
}
