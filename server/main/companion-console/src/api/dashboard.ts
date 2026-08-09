import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'

export interface RecentSession {
  sessionId: string
  title: string
  createdAt: string
  chatCount: number
}

interface RequestOptions { signal?: AbortSignal }

function record(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

export async function listRecentSessions(profileId: string, options?: RequestOptions): Promise<RecentSession[]> {
  const response = await http.get<ApiResult<unknown>>(`/agent/${encodeURIComponent(profileId)}/sessions`, {
    params: { page: 1, limit: 3 },
    ...(options?.signal ? { signal: options.signal } : {}),
  })
  const result: unknown = response.data
  if (!record(result) || typeof result.code !== 'number' || typeof result.msg !== 'string' || !('data' in result)) {
    throw new ApiProtocolError('最近对话响应格式错误', result, response.config)
  }
  if (result.code !== 0) throw new ApiError(result.code, result.msg || '最近对话加载失败', result.data, response.config)
  if (!record(result.data) || !Array.isArray(result.data.list)) {
    throw new ApiProtocolError('最近对话数据格式错误', result.data, response.config)
  }
  return result.data.list.map((item): RecentSession => {
    if (!record(item) || typeof item.sessionId !== 'string' || !item.sessionId
      || (item.title !== null && item.title !== undefined && typeof item.title !== 'string')
      || typeof item.createdAt !== 'string' || Number.isNaN(Date.parse(item.createdAt))
      || typeof item.chatCount !== 'number' || !Number.isInteger(item.chatCount) || item.chatCount < 0) {
      throw new ApiProtocolError('最近对话字段错误', item, response.config)
    }
    return {
      sessionId: item.sessionId,
      title: typeof item.title === 'string' && item.title.trim() ? item.title.trim() : '未命名对话',
      createdAt: item.createdAt,
      chatCount: item.chatCount,
    }
  })
}
