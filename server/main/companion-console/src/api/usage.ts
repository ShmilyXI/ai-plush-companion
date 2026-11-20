import http, { type ApiResult } from './http'
import { isRecord, protocolError, unwrap } from './zixuanModels'

export interface DailyUsage {
  usageDate: string
  users: number
  turns: number
  audioTurns: number
  /** 角色主动说的开场白，不算用户的一轮。 */
  openings: number
  promptTokens: number
  completionTokens: number
  ttsChars: number
  /** 从收到输入到出现第一段文字的平均毫秒数；没有数据时为 null。 */
  avgFirstTextMs: number | null
}

export interface UserUsage {
  userId: string
  turns: number
  audioTurns: number
  promptTokens: number
  completionTokens: number
}

interface RequestOptions { signal?: AbortSignal }

function requestConfig(options?: RequestOptions) {
  return options?.signal ? { signal: options.signal } : undefined
}

/** 服务端把大整数和求和结果写成数字或数字字符串，这里统一成数字。 */
function count(value: unknown) {
  const parsed = typeof value === 'number' ? value : typeof value === 'string' && value.trim() ? Number(value) : NaN
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

export async function listDailyUsage(days = 14, options?: RequestOptions): Promise<DailyUsage[]> {
  const response = await http.get<ApiResult<unknown>>('/admin/usage/daily', { params: { days }, ...requestConfig(options) })
  const data = unwrap(response)
  if (!Array.isArray(data)) throw protocolError('用量数据格式错误', data, response)
  return data.map((item) => {
    if (!isRecord(item) || typeof item.usageDate !== 'string' || count(item.turns) === null) {
      throw protocolError('用量数据字段错误', item, response)
    }
    return {
      usageDate: item.usageDate,
      users: count(item.users) ?? 0,
      turns: count(item.turns) ?? 0,
      audioTurns: count(item.audioTurns) ?? 0,
      openings: count(item.openings) ?? 0,
      promptTokens: count(item.promptTokens) ?? 0,
      completionTokens: count(item.completionTokens) ?? 0,
      ttsChars: count(item.ttsChars) ?? 0,
      avgFirstTextMs: count(item.avgFirstTextMs),
    }
  })
}

export async function listUserUsage(date: string, options?: RequestOptions): Promise<UserUsage[]> {
  const response = await http.get<ApiResult<unknown>>('/admin/usage/users', { params: { date, limit: 50 }, ...requestConfig(options) })
  const data = unwrap(response)
  if (!Array.isArray(data)) throw protocolError('用量数据格式错误', data, response)
  return data.map((item) => {
    if (!isRecord(item) || (typeof item.userId !== 'string' && typeof item.userId !== 'number') || count(item.turns) === null) {
      throw protocolError('用量数据字段错误', item, response)
    }
    return {
      userId: String(item.userId),
      turns: count(item.turns) ?? 0,
      audioTurns: count(item.audioTurns) ?? 0,
      promptTokens: count(item.promptTokens) ?? 0,
      completionTokens: count(item.completionTokens) ?? 0,
    }
  })
}
