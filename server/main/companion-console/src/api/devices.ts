import type { AxiosResponse } from 'axios'

import http, { ApiError, type ApiResult } from './http'

export interface CompanionDevice {
  id: string
  macAddress: string
  alias: string | null
  online: boolean
  appVersion: string | null
  hasDisplay: boolean
  hasCamera: boolean
  activeProfileId: string | null
  debugLogEnabled: boolean
  board?: string | null
  lastConnectedAt?: string | null
  effectiveModels?: EffectiveDeviceModel[]
}

export interface EffectiveDeviceModel {
  modelType: 'LLM' | 'ASR' | 'TTS' | 'VAD' | 'VLLM' | 'Memory'
  resourceId: string | null
  name: string
  source: 'default' | 'global' | 'private' | 'legacy'
  modelId: string | null
  overridden: boolean
  overrides: Record<string, unknown>
}

export interface CompanionProfileSummary {
  id: string
  name: string
}

export interface BindDeviceInput {
  activationCode: string
  profileId?: string
}

export type WakeWordStatus = 'IDLE' | 'GENERATING' | 'WAITING_DEVICE' | 'DOWNLOADING' | 'WAITING_REBOOT' | 'ACTIVE' | 'FAILED'

export interface DeviceWakeWordState {
  desiredWord: string | null
  desiredVersion: number
  activeWord: string | null
  activeVersion: number
  status: WakeWordStatus
  lastErrorCode: string | null
  lastErrorMessage: string | null
  supported: boolean
  unsupportedReason: string | null
  updatedAt: string | null
}

interface RequestOptions {
  signal?: AbortSignal
}

export class ApiProtocolError extends ApiError {
  constructor(message: string, data: unknown, config?: AxiosResponse['config']) {
    super(-2, message, data, config)
    this.name = 'ApiProtocolError'
  }
}

export class DeviceCommandError extends ApiError {
  constructor(data: unknown, config?: AxiosResponse['config']) {
    super(10206, '设备命令执行失败', data, config)
    this.name = 'DeviceCommandError'
  }
}

function unwrap<T>(response: AxiosResponse<ApiResult<T>>): T {
  const result: unknown = response.data
  if (
    !isRecord(result)
    || typeof result.code !== 'number'
    || typeof result.msg !== 'string'
    || !('data' in result)
  ) {
    throw new ApiProtocolError('响应数据格式错误', result, response.config)
  }
  if (result.code !== 0) {
    throw new ApiError(result.code, result.msg || '请求失败', result.data, response.config)
  }
  return result.data as T
}

function requestConfig(options?: RequestOptions) {
  return options?.signal ? { signal: options.signal } : undefined
}

function idString(value: unknown) {
  if ((typeof value === 'string' && value.length > 0) || (typeof value === 'number' && Number.isSafeInteger(value))) {
    return String(value)
  }
  return null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

function optionalString(value: unknown) {
  return value === null || value === undefined || typeof value === 'string'
}

function parseEffectiveModels(value: unknown, response: AxiosResponse): EffectiveDeviceModel[] {
  if (value === null || value === undefined) return []
  if (!Array.isArray(value)) throw new ApiProtocolError('设备生效模型格式错误', value, response.config)
  const types = ['LLM', 'ASR', 'TTS', 'VAD', 'VLLM', 'Memory']
  const sources = ['default', 'global', 'private', 'legacy']
  return value.map((item) => {
    if (!isRecord(item) || !types.includes(String(item.modelType)) || !optionalString(item.resourceId)
      || typeof item.name !== 'string' || !sources.includes(String(item.source)) || !optionalString(item.modelId)
      || typeof item.overridden !== 'boolean' || (item.overrides !== null && item.overrides !== undefined && !isRecord(item.overrides))) {
      throw new ApiProtocolError('设备生效模型字段错误', item, response.config)
    }
    return { modelType: item.modelType as EffectiveDeviceModel['modelType'], resourceId: item.resourceId ?? null,
      name: item.name, source: item.source as EffectiveDeviceModel['source'], modelId: item.modelId ?? null,
      overridden: item.overridden, overrides: item.overrides ?? {} }
  })
}

function parseDevice(value: unknown, response: AxiosResponse): CompanionDevice {
  if (!isRecord(value)) throw new ApiProtocolError('设备数据格式错误', value, response.config)
  const id = idString(value.id)
  const activeProfileId = value.activeProfileId === null || value.activeProfileId === undefined
    ? null
    : idString(value.activeProfileId)
  if (
    !id
    || !('macAddress' in value)
    || typeof value.macAddress !== 'string'
    || !('alias' in value)
    || !optionalString(value.alias)
    || !('online' in value)
    || typeof value.online !== 'boolean'
    || !('appVersion' in value)
    || !optionalString(value.appVersion)
    || !('hasDisplay' in value)
    || typeof value.hasDisplay !== 'boolean'
    || !('hasCamera' in value)
    || typeof value.hasCamera !== 'boolean'
    || !('activeProfileId' in value)
    || (value.activeProfileId !== null && value.activeProfileId !== undefined && !activeProfileId)
    || !('debugLogEnabled' in value)
    || typeof value.debugLogEnabled !== 'boolean'
    || !optionalString(value.board)
    || !optionalString(value.lastConnectedAt)
  ) {
    throw new ApiProtocolError('设备数据字段错误', value, response.config)
  }
  return {
    id,
    macAddress: value.macAddress,
    alias: value.alias ?? null,
    online: value.online,
    appVersion: value.appVersion ?? null,
    hasDisplay: value.hasDisplay,
    hasCamera: value.hasCamera,
    activeProfileId,
    debugLogEnabled: value.debugLogEnabled,
    board: value.board ?? null,
    lastConnectedAt: value.lastConnectedAt ?? null,
    effectiveModels: parseEffectiveModels(value.effectiveModels, response),
  }
}

function parseProfiles(value: unknown, response: AxiosResponse): CompanionProfileSummary[] {
  if (!Array.isArray(value)) throw new ApiProtocolError('陪伴角色数据格式错误', value, response.config)
  return value.map((profile) => {
    if (!isRecord(profile) || !idString(profile.id) || typeof profile.name !== 'string') {
      throw new ApiProtocolError('陪伴角色数据字段错误', profile, response.config)
    }
    return { id: idString(profile.id)!, name: profile.name }
  })
}

function encodedId(id: string) {
  return encodeURIComponent(id)
}

function nonNegativeSafeInteger(value: unknown): number | null {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return value
  if (typeof value !== 'string' || !/^(0|[1-9]\d*)$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}

function parseWakeWordState(value: unknown, response: AxiosResponse): DeviceWakeWordState {
  const statuses: WakeWordStatus[] = ['IDLE', 'GENERATING', 'WAITING_DEVICE', 'DOWNLOADING', 'WAITING_REBOOT', 'ACTIVE', 'FAILED']
  if (!isRecord(value)) throw new ApiProtocolError('唤醒词状态字段错误', value, response.config)
  const desiredVersion = nonNegativeSafeInteger(value.desiredVersion)
  const activeVersion = nonNegativeSafeInteger(value.activeVersion)
  if (!optionalString(value.desiredWord)
    || desiredVersion === null
    || !optionalString(value.activeWord)
    || activeVersion === null
    || !statuses.includes(value.status as WakeWordStatus)
    || !optionalString(value.lastErrorCode)
    || !optionalString(value.lastErrorMessage)
    || typeof value.supported !== 'boolean'
    || !optionalString(value.unsupportedReason)
    || !optionalString(value.updatedAt)) {
    throw new ApiProtocolError('唤醒词状态字段错误', value, response.config)
  }
  return {
    desiredWord: value.desiredWord ?? null,
    desiredVersion,
    activeWord: value.activeWord ?? null,
    activeVersion,
    status: value.status as WakeWordStatus,
    lastErrorCode: value.lastErrorCode ?? null,
    lastErrorMessage: value.lastErrorMessage ?? null,
    supported: value.supported,
    unsupportedReason: value.unsupportedReason ?? null,
    updatedAt: value.updatedAt ?? null,
  }
}

export async function listDevices(options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/companion/devices', requestConfig(options))
  const data = unwrap(response)
  if (!Array.isArray(data)) throw new ApiProtocolError('设备列表数据格式错误', data, response.config)
  return data.map((device) => parseDevice(device, response))
}

export async function getDevice(id: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/companion/devices/${encodedId(id)}`, requestConfig(options))
  return parseDevice(unwrap(response), response)
}

export async function bindDevice(input: BindDeviceInput, options?: RequestOptions) {
  unwrap(await http.post<ApiResult<null>>('/companion/devices/bind', input, requestConfig(options)))
}

export async function updateDevice(id: string, input: { alias: string }, options?: RequestOptions) {
  unwrap(await http.put<ApiResult<null>>(`/companion/devices/${encodedId(id)}`, input, requestConfig(options)))
}

export async function switchDeviceProfile(id: string, profileId: string, options?: RequestOptions) {
  unwrap(await http.put<ApiResult<null>>(`/companion/devices/${encodedId(id)}/profile`, { profileId }, requestConfig(options)))
}

export async function unbindDevice(id: string, options?: RequestOptions) {
  unwrap(await http.delete<ApiResult<null>>(`/companion/devices/${encodedId(id)}`, requestConfig(options)))
}

export async function sendDeviceCommand(id: string, command: 'volume' | 'brightness', value: number, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(`/companion/devices/${encodedId(id)}/commands`, { command, value }, requestConfig(options))
  const result = unwrap(response)
  if (result === true) return result
  if (
    result
    && typeof result === 'object'
    && !Array.isArray(result)
    && (result as { success?: unknown }).success === true
    && ((result as { isError?: unknown }).isError === undefined || (result as { isError?: unknown }).isError === false)
    && !(result as { error?: unknown }).error
  ) {
    return result
  }
  throw new DeviceCommandError(result, response.config)
}

export async function setDeviceDebugLogging(id: string, enabled: boolean, options?: RequestOptions) {
  unwrap(await http.put<ApiResult<null>>(
    `/companion/devices/${encodedId(id)}/debug-logs/settings`,
    { enabled },
    requestConfig(options),
  ))
}

export async function getDeviceWakeWord(id: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/companion/devices/${encodedId(id)}/wake-word`, requestConfig(options))
  return parseWakeWordState(unwrap(response), response)
}

export async function updateDeviceWakeWord(id: string, word: string, options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(
    `/companion/devices/${encodedId(id)}/wake-word`, { word }, requestConfig(options),
  )
  return parseWakeWordState(unwrap(response), response)
}

export async function retryDeviceWakeWord(id: string, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(
    `/companion/devices/${encodedId(id)}/wake-word/retry`, undefined, requestConfig(options),
  )
  return parseWakeWordState(unwrap(response), response)
}

export async function listProfiles(options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/companion/profiles', requestConfig(options))
  return parseProfiles(unwrap(response), response)
}
