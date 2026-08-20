import type { AxiosResponse } from 'axios'

import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'

export interface CompanionMemory {
  id: string
  content: string
  updatedAt: string
  sourceDeviceId: string | null
  sourceProfileId: string | null
  sourceDeviceName: string | null
  sourceProfileName: string | null
}
export interface MemoryMigrationPreview {
  agentId: string
  sourceDeviceId: string
  targetDeviceId: string
  sourceCount: number
  targetCount: number
  mode: 'merge' | 'overwrite'
}
export interface MemoryMigrationResult extends MemoryMigrationPreview {
  id: string
  importedCount: number
  skippedCount: number
  outcome: string
  retryable: boolean
  recovered: boolean
  operatorId: number
  createdAt: string
}
interface RequestOptions { signal?: AbortSignal }

function isRecord(value: unknown): value is Record<string, unknown> { return Boolean(value && typeof value === 'object' && !Array.isArray(value)) }
function encoded(value: string) { return encodeURIComponent(value) }
function requestConfig(options?: RequestOptions) { return options?.signal ? { signal: options.signal } : undefined }
function nullableString(value: unknown) { return value === null || value === undefined || typeof value === 'string' }
function unwrap(response: AxiosResponse<ApiResult<unknown>>) {
  const result: unknown = response.data
  if (!isRecord(result) || typeof result.code !== 'number' || typeof result.msg !== 'string' || !('data' in result)) throw new ApiProtocolError('响应数据格式错误', result, response.config)
  if (result.code !== 0) throw new ApiError(result.code, result.msg || '请求失败', result.data, response.config)
  return result.data
}

export async function listMemories(deviceId: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/companion/devices/${encoded(deviceId)}/memories`, requestConfig(options))
  const data = unwrap(response)
  if (!Array.isArray(data)) throw new ApiProtocolError('记忆列表数据格式错误', data, response.config)
  return data.map((item): CompanionMemory => {
    if (!isRecord(item) || typeof item.id !== 'string' || !item.id || typeof item.content !== 'string' || typeof item.updated_at !== 'string'
      || !nullableString(item.source_device_id) || !nullableString(item.source_profile_id)
      || !nullableString(item.sourceDeviceName) || !nullableString(item.sourceProfileName)) throw new ApiProtocolError('记忆数据字段错误', item, response.config)
    return {
      id: item.id, content: item.content, updatedAt: item.updated_at,
      sourceDeviceId: item.source_device_id ?? null, sourceProfileId: item.source_profile_id ?? null,
      sourceDeviceName: item.sourceDeviceName ?? null, sourceProfileName: item.sourceProfileName ?? null,
    }
  })
}

export async function updateMemory(deviceId: string, memoryId: string, content: string, options?: RequestOptions) {
  unwrap(await http.put<ApiResult<unknown>>(`/companion/devices/${encoded(deviceId)}/memories/${encoded(memoryId)}`, { content }, requestConfig(options)))
}
export async function deleteMemory(deviceId: string, memoryId: string, options?: RequestOptions) {
  unwrap(await http.delete<ApiResult<unknown>>(`/companion/devices/${encoded(deviceId)}/memories/${encoded(memoryId)}`, requestConfig(options)))
}
export async function clearMemories(deviceId: string, options?: RequestOptions) {
  unwrap(await http.delete<ApiResult<unknown>>(`/companion/devices/${encoded(deviceId)}/memories`, requestConfig(options)))
}

export async function previewMemoryMigration(sourceDeviceId: string, targetDeviceId: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/companion/memory-migrations/preview', {
    ...requestConfig(options), params: { sourceDeviceId, targetDeviceId },
  })
  const data = unwrap(response)
  if (!isRecord(data) || typeof data.agentId !== 'string' || typeof data.sourceCount !== 'number' || typeof data.targetCount !== 'number') throw new ApiProtocolError('迁移预览数据格式错误', data, response.config)
  return data as unknown as MemoryMigrationPreview
}

export async function migrateMemories(sourceDeviceId: string, targetDeviceId: string, mode: 'merge' | 'overwrite', options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>('/companion/memory-migrations', { sourceDeviceId, targetDeviceId, mode }, requestConfig(options))
  const data = unwrap(response)
  if (!isRecord(data) || typeof data.id !== 'string' || typeof data.outcome !== 'string' || typeof data.importedCount !== 'number') throw new ApiProtocolError('迁移结果数据格式错误', data, response.config)
  return data as unknown as MemoryMigrationResult
}

export async function listMemoryMigrations(options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/companion/memory-migrations', requestConfig(options))
  const data = unwrap(response)
  if (!Array.isArray(data)) throw new ApiProtocolError('迁移历史数据格式错误', data, response.config)
  return data as unknown as MemoryMigrationResult[]
}

export async function retryMemoryMigration(migrationId: string, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(`/companion/memory-migrations/${encoded(migrationId)}/retry`, undefined, requestConfig(options))
  const data = unwrap(response)
  if (!isRecord(data) || typeof data.id !== 'string' || typeof data.outcome !== 'string') throw new ApiProtocolError('重试结果数据格式错误', data, response.config)
  return data as unknown as MemoryMigrationResult
}
