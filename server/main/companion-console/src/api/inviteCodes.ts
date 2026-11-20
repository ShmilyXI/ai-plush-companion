import http, { type ApiResult } from './http'
import { isRecord, protocolError, unwrap } from './zixuanModels'

export interface InviteCode {
  id: string
  code: string
  maxUses: number
  usedCount: number
  expireDate: string | null
  enabled: boolean
  /** 现在是否还能用：启用、未过期且还有名额。 */
  usable: boolean
  remark: string | null
  createDate: string | null
}

export interface InviteCodeCreateInput {
  count: number
  maxUses: number
  expireDate?: string | null
  remark?: string | null
}

interface RequestOptions { signal?: AbortSignal }

function requestConfig(options?: RequestOptions) {
  return options?.signal ? { signal: options.signal } : undefined
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function optionalString(value: unknown): value is string | null | undefined {
  return value === null || value === undefined || typeof value === 'string'
}

function parseCode(value: unknown, response: Parameters<typeof protocolError>[2]): InviteCode {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id
    || typeof value.code !== 'string' || !value.code
    || !isCount(value.maxUses) || !isCount(value.usedCount)
    || typeof value.enabled !== 'boolean' || typeof value.usable !== 'boolean'
    || !optionalString(value.expireDate) || !optionalString(value.remark) || !optionalString(value.createDate)) {
    throw protocolError('邀请码数据字段错误', value, response)
  }
  return {
    id: value.id,
    code: value.code,
    maxUses: value.maxUses,
    usedCount: value.usedCount,
    expireDate: value.expireDate ?? null,
    enabled: value.enabled,
    usable: value.usable,
    remark: value.remark ?? null,
    createDate: value.createDate ?? null,
  }
}

export async function listInviteCodes(page: number, limit: number, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/admin/invite-codes', { params: { page, limit }, ...requestConfig(options) })
  const data = unwrap(response)
  if (!isRecord(data) || !isCount(data.total) || !Array.isArray(data.list)) {
    throw protocolError('邀请码列表格式错误', data, response)
  }
  return { total: data.total, list: data.list.map((item) => parseCode(item, response)) }
}

export async function createInviteCodes(input: InviteCodeCreateInput, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>('/admin/invite-codes', input, requestConfig(options))
  const data = unwrap(response)
  if (!Array.isArray(data)) throw protocolError('邀请码列表格式错误', data, response)
  return data.map((item) => parseCode(item, response))
}

export async function setInviteCodeEnabled(id: string, enabled: boolean, options?: RequestOptions) {
  unwrap(await http.put<ApiResult<unknown>>(
    `/admin/invite-codes/${encodeURIComponent(id)}/enabled/${enabled}`, undefined, requestConfig(options)))
}

export async function getInviteRequired(options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/admin/invite-codes/required', requestConfig(options))
  const data = unwrap(response)
  if (!isRecord(data) || typeof data.required !== 'boolean') throw protocolError('邀请制状态格式错误', data, response)
  return data.required
}

export async function setInviteRequired(required: boolean, options?: RequestOptions) {
  unwrap(await http.put<ApiResult<unknown>>(`/admin/invite-codes/required/${required}`, undefined, requestConfig(options)))
}
