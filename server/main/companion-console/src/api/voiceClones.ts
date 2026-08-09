import type { AxiosResponse } from 'axios'

import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'
import { parseVoiceResourcePage, type VoiceResourceListParams } from './voiceResources'

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

function id(value: string, label = '音色 ID') {
  if (typeof value !== 'string' || !value.trim()) throw new TypeError(`${label} 不能为空`)
  return value
}

function params(value: VoiceResourceListParams) {
  if (!Number.isSafeInteger(value.page) || value.page < 1 || !Number.isSafeInteger(value.limit) || value.limit < 1
    || typeof value.name !== 'string') throw new TypeError('分页参数错误')
  return {
    page: value.page,
    limit: value.limit,
    name: value.name,
    ...(value.orderField ? { orderField: value.orderField } : {}),
    ...(value.order ? { order: value.order } : {}),
  }
}

export async function listVoiceClones(value: VoiceResourceListParams, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/voiceClone', { params: params(value), ...config(options) })
  return parseVoiceResourcePage(unwrap(response), response)
}

export async function uploadVoiceSample(cloneId: string, file: File, options?: RequestOptions) {
  id(cloneId)
  validateVoiceSample(file)
  const body = new FormData()
  body.append('id', cloneId)
  body.append('voiceFile', file)
  unwrap(await http.post<ApiResult<unknown>>('/voiceClone/upload', body, {
    headers: { 'Content-Type': 'multipart/form-data' },
    ...config(options),
  }))
}

export function validateVoiceSample(file: File) {
  if (!(file instanceof File)) throw new TypeError('请选择音频文件')
  const extension = file.name.match(/(\.[^.]+)$/)?.[1]?.toLowerCase()
  const allowedMime = new Set(['audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/x-wav', 'audio/wave'])
  if (!['.mp3', '.wav'].includes(extension ?? '') || !allowedMime.has(file.type.toLowerCase())) {
    throw new TypeError('只允许上传 MP3 或 WAV 文件')
  }
  if (file.size > 10 * 1024 * 1024) throw new RangeError('音频文件不能超过 10MB')
}

export async function updateVoiceCloneName(cloneId: string, name: string, options?: RequestOptions) {
  id(cloneId)
  if (typeof name !== 'string' || !name.trim()) throw new TypeError('音色名称不能为空')
  unwrap(await http.post<ApiResult<unknown>>('/voiceClone/updateName', { id: cloneId, name: name.trim() }, config(options)))
}

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

export async function getVoiceAudioUuid(cloneId: string, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(`/voiceClone/audio/${encodeURIComponent(id(cloneId))}`, undefined, config(options))
  const data = unwrap(response)
  if (typeof data !== 'string' || !UUID_V4.test(data)) throw new ApiProtocolError('试听凭证格式错误', data, response.config)
  return data
}

export function getVoiceClonePlayUrl(uuid: string) {
  if (!UUID_V4.test(uuid)) throw new TypeError('试听凭证格式错误')
  const base = new URL(String(http.defaults.baseURL || '/'), window.location.origin)
  if (base.protocol !== 'http:' && base.protocol !== 'https:') throw new TypeError('试听地址不安全')
  const path = `${base.pathname.replace(/\/$/, '')}/voiceClone/play/${uuid}`
  const url = new URL(path, base.origin)
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new TypeError('试听地址不安全')
  return url.toString()
}

export async function cloneVoiceAudio(cloneId: string, options?: RequestOptions) {
  unwrap(await http.post<ApiResult<unknown>>('/voiceClone/cloneAudio', { cloneId: id(cloneId) }, config(options)))
}
