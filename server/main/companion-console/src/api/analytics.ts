import http, { type ApiResult } from './http'
import { isRecord, protocolError, unwrap } from './zixuanModels'

export interface Cohort {
  /** 注册日期。 */
  date: string
  size: number
  /** 注册后至少聊过一轮的人数。 */
  activated: number
  /** 注册后第 N 天当天回来聊过的人数。那一天还没过完时为 null。 */
  d1: number | null
  d7: number | null
  d30: number | null
}

export interface DailyLatency {
  date: string
  turns: number
  /** 首字耗时的中位数和 90 分位，毫秒。 */
  p50: number
  p90: number
}

export interface EventCount {
  date: string
  name: string
  total: number
  users: number
}

export interface AnalyticsOverview {
  cohorts: Cohort[]
  latency: DailyLatency[]
  events: EventCount[]
}

function count(value: unknown) {
  const parsed = typeof value === 'number' ? value : typeof value === 'string' && value.trim() ? Number(value) : NaN
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

export async function getAnalyticsOverview(days = 30, options?: { signal?: AbortSignal }): Promise<AnalyticsOverview> {
  const response = await http.get<ApiResult<unknown>>('/admin/analytics/overview', {
    params: { days }, ...(options?.signal ? { signal: options.signal } : {}),
  })
  const data = unwrap(response)
  if (!isRecord(data) || !Array.isArray(data.cohorts) || !Array.isArray(data.latency) || !Array.isArray(data.events)) {
    throw protocolError('数据总览格式错误', data, response)
  }
  const fail = (item: unknown) => protocolError('数据总览字段错误', item, response)
  return {
    cohorts: data.cohorts.map((item) => {
      if (!isRecord(item) || typeof item.date !== 'string' || count(item.size) === null) throw fail(item)
      return {
        date: item.date,
        size: count(item.size) ?? 0,
        activated: count(item.activated) ?? 0,
        d1: count(item.d1),
        d7: count(item.d7),
        d30: count(item.d30),
      }
    }),
    latency: data.latency.map((item) => {
      if (!isRecord(item) || typeof item.date !== 'string' || count(item.p50) === null || count(item.p90) === null) throw fail(item)
      return { date: item.date, turns: count(item.turns) ?? 0, p50: count(item.p50) ?? 0, p90: count(item.p90) ?? 0 }
    }),
    events: data.events.map((item) => {
      if (!isRecord(item) || typeof item.date !== 'string' || typeof item.name !== 'string') throw fail(item)
      return { date: item.date, name: item.name, total: count(item.total) ?? 0, users: count(item.users) ?? 0 }
    }),
  }
}
