import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'

export interface CompanionSubscription {
  planId: string; planCode: string; planName: string; maxDevices: number; maxProfiles: number
  longTermMemory: boolean; advancedVoice: boolean; expiresAt: string | null
}
interface RequestOptions { signal?: AbortSignal }
function isRecord(value: unknown): value is Record<string, unknown> { return Boolean(value && typeof value === 'object' && !Array.isArray(value)) }
export async function getSubscription(options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/companion/subscription', options?.signal ? { signal: options.signal } : undefined)
  const result: unknown = response.data
  if (!isRecord(result) || typeof result.code !== 'number' || typeof result.msg !== 'string' || !('data' in result)) throw new ApiProtocolError('响应数据格式错误', result, response.config)
  if (result.code !== 0) throw new ApiError(result.code, result.msg || '请求失败', result.data, response.config)
  const data = result.data
  if (!isRecord(data) || typeof data.planId !== 'string' || typeof data.planCode !== 'string' || typeof data.planName !== 'string'
    || typeof data.maxDevices !== 'number' || typeof data.maxProfiles !== 'number' || typeof data.longTermMemory !== 'boolean'
    || typeof data.advancedVoice !== 'boolean'
    || (data.expiresAt !== null && data.expiresAt !== undefined
      && (typeof data.expiresAt !== 'string' || !data.expiresAt.trim() || Number.isNaN(Date.parse(data.expiresAt))))) {
    throw new ApiProtocolError('订阅数据字段错误', data, response.config)
  }
  return { ...data, expiresAt: data.expiresAt ?? null } as CompanionSubscription
}
