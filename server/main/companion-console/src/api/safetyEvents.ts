import http, { type ApiResult } from './http'
import { isRecord, protocolError, unwrap } from './zixuanModels'

export interface SafetyEvent {
  id: string
  userId: string
  agentId: string
  direction: 'input' | 'output'
  action: 'block' | 'crisis'
  category: string
  createdAt: string | null
}

interface RequestOptions { signal?: AbortSignal }

function idText(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : ''
}

export async function listSafetyEvents(page: number, limit: number, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/admin/safety-events', {
    params: { page, limit }, ...(options?.signal ? { signal: options.signal } : {}),
  })
  const data = unwrap(response)
  if (!isRecord(data) || typeof data.total !== 'number' || !Array.isArray(data.list)) {
    throw protocolError('安全事件列表格式错误', data, response)
  }
  const list: SafetyEvent[] = data.list.map((item) => {
    if (!isRecord(item) || !idText(item.id) || !idText(item.userId)
      || (item.direction !== 'input' && item.direction !== 'output')
      || (item.action !== 'block' && item.action !== 'crisis')
      || typeof item.category !== 'string') {
      throw protocolError('安全事件数据字段错误', item, response)
    }
    return {
      id: idText(item.id),
      userId: idText(item.userId),
      agentId: typeof item.agentId === 'string' ? item.agentId : '',
      direction: item.direction,
      action: item.action,
      category: item.category,
      createdAt: typeof item.createdAt === 'string' ? item.createdAt : null,
    }
  })
  return { total: data.total, list }
}
