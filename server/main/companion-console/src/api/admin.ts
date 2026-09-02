import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'
import type { CompanionMemory } from './memories'

export interface PageResult<T> { list: T[]; total: number }
export interface AdminUser { id: string; username?: string; mobile: string; status: number; deviceCount?: string; createDate?: string }
export type CompanionMode = 'turn_based' | 'proactive'
export interface AdminDevice { id: string; alias?: string; macAddress?: string; bindUserName?: string; deviceType?: string; board?: string; appVersion?: string; lastConnectedAtTimestamp?: number; companionMode?: CompanionMode }
export interface AdminTemplate { id: string; agentCode?: string; agentName: string; description?: string; systemPrompt?: string; sort?: number; raw: Record<string, unknown> }
export interface AdminResource { id: string; name: string; type: string; modelCode?: string; providerCode?: string; enabled: boolean; isDefault: boolean; docLink?: string; remark?: string; sort?: number; profileUsageCount?: number; deviceUsageCount?: number }
export interface TimbreResource { id: string; name: string; languages: string; ttsModelId: string; ttsVoice: string; remark?: string; sort: number; voiceDemo?: string }
export interface Firmware { id: string; firmwareName: string; version: string; type: string; remark?: string; firmwarePath?: string }
export interface TemplateInput { agentCode: string; agentName: string; systemPrompt?: string }
export interface FirmwareInput { firmwareName: string; version: string; type: string; remark?: string }
export interface ModelCreateInput { modelType: string; providerCode: string; modelCode: string; modelName: string; isEnabled: number; isDefault: number; docLink?: string; remark?: string; sort?: number; config?: Record<string, unknown> }
export interface ModelUpdateInput { modelName: string; isEnabled: number; remark?: string; sort?: number; config?: Record<string, unknown> }
export interface TimbreInput { name: string; languages: string; ttsModelId: string; ttsVoice: string; remark?: string; sort: number; voiceDemo?: string }
export interface CompanionPlan { id: string; planCode: string; planName: string; maxDevices: number; maxProfiles: number; longTermMemory: number; advancedVoice: number; status: number }
export interface AuditRow { id: string; operatorId: number; targetUserId: number | null; action: string; resourceType: string; resourceId: string | null; summary: string; createdAt: string }
export interface PlanInput { planCode: string; planName: string; maxDevices: number; maxProfiles: number; longTermMemory: number; advancedVoice: number; status: number }
export type HealthStatus = 'available' | 'unavailable' | 'unknown'
export interface SystemSettingOption { id: string; name: string; type: string }
export interface ServiceHealth { status: HealthStatus; address: string; checkedAt: string }
export interface SystemSettingsInput {
  publicWebsocketUrl: string
  publicOtaUrl: string
  xiaozhiListenHost: string
  xiaozhiListenPort: number
  otaListenHost: string
  otaListenPort: number
  defaultLlmModelId: string
  defaultVllmModelId?: string
  defaultTtsModelId: string
  defaultAsrModelId: string
  defaultVadModelId: string
  defaultMemoryModelId?: string
  defaultTtsVoiceId?: string
  proactivePlannerPrompt?: string
}
export interface SystemSettings extends SystemSettingsInput {
  modelOptions: Record<string, SystemSettingOption[]>
  voices: SystemSettingOption[]
  health: Record<'xiaozhi' | 'ota', ServiceHealth>
  restartRequired: boolean
  restartServices: string[]
}
interface Options { signal?: AbortSignal }

function record(value: unknown): value is Record<string, unknown> { return Boolean(value && typeof value === 'object' && !Array.isArray(value)) }
function text(value: unknown) { return typeof value === 'string' ? value : '' }
function number(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value : Number(value) }
function id(value: unknown) { const result = String(value ?? ''); if (!result) throw new ApiProtocolError('响应缺少资源 ID', value); return result }
function maskMobile(value: unknown) { const mobile = text(value); return /^\d{11}$/.test(mobile) ? `${mobile.slice(0, 3)}****${mobile.slice(-4)}` : mobile }
function config(options?: Options) { return options?.signal ? { signal: options.signal } : undefined }
function path(value: string | number) { return encodeURIComponent(String(value)) }

function unwrap(payload: unknown, requestConfig?: unknown) {
  if (!record(payload) || typeof payload.code !== 'number' || typeof payload.msg !== 'string' || !('data' in payload)) {
    throw new ApiProtocolError('响应数据格式错误', payload, requestConfig as never)
  }
  if (payload.code !== 0) throw new ApiError(payload.code, payload.msg || '请求失败', payload.data, requestConfig as never)
  return payload.data
}

async function getData(url: string, params: Record<string, unknown>, options?: Options) {
  const response = await http.get<ApiResult<unknown>>(url, { params, ...config(options) })
  return unwrap(response.data, response.config)
}
function pageData(value: unknown) {
  if (!record(value) || !Array.isArray(value.list) || !Number.isFinite(number(value.total))) throw new ApiProtocolError('分页数据格式错误', value)
  return { list: value.list, total: number(value.total) }
}

export async function listUsers(query = '', page = 1, limit = 20, options?: Options): Promise<PageResult<AdminUser>> {
  const data = pageData(await getData('/admin/companion/users', { keyword: query, page, limit }, options))
  return { total: data.total, list: data.list.map((item) => { if (!record(item)) throw new ApiProtocolError('用户数据格式错误', item); return { id: id(item.userid ?? item.id), username: text(item.username) || undefined, mobile: maskMobile(item.mobile), status: number(item.status), deviceCount: text(item.deviceCount), createDate: text(item.createDate) || undefined } }) }
}
export async function changeUserStatus(userId: string, status: number) { await http.put(`/admin/companion/users/${path(userId)}/status/${status}`) }
export async function listDevices(query = '', page = 1, limit = 20, options?: Options): Promise<PageResult<AdminDevice>> {
  const data = pageData(await getData('/admin/device/all', { keywords: query, page, limit }, options))
  return { total: data.total, list: data.list.map((item) => { if (!record(item)) throw new ApiProtocolError('设备数据格式错误', item); const mode = item.companionMode === 'proactive' ? 'proactive' : 'turn_based'; return { id: id(item.id), alias: text(item.alias), macAddress: text(item.macAddress), bindUserName: text(item.bindUserName), deviceType: text(item.deviceType), board: text(item.board), appVersion: text(item.appVersion), lastConnectedAtTimestamp: number(item.lastConnectedAtTimestamp) || undefined, companionMode: mode } }) }
}
export async function listAdminMemories(deviceId: string, options?: Options): Promise<CompanionMemory[]> {
  const response = await http.get<ApiResult<unknown>>(`/admin/companion/devices/${path(deviceId)}/memories`, config(options))
  const data = unwrap(response.data, response.config)
  if (!Array.isArray(data)) throw new ApiProtocolError('记忆列表数据格式错误', data, response.config)
  return data.map(adminMemory)
}
export async function updateAdminMemory(deviceId: string, memoryId: string, content: string, options?: Options) {
  const response = await http.put<ApiResult<unknown>>(`/admin/companion/devices/${path(deviceId)}/memories/${path(memoryId)}`, { content }, config(options))
  unwrap(response.data, response.config)
}
export async function deleteAdminMemory(deviceId: string, memoryId: string, options?: Options) {
  const response = await http.delete<ApiResult<unknown>>(`/admin/companion/devices/${path(deviceId)}/memories/${path(memoryId)}`, config(options))
  unwrap(response.data, response.config)
}
export async function clearAdminMemories(deviceId: string, options?: Options) {
  const response = await http.delete<ApiResult<unknown>>(`/admin/companion/devices/${path(deviceId)}/memories`, config(options))
  unwrap(response.data, response.config)
}
export async function renameAdminDevice(deviceId: string, alias: string) { await http.put(`/admin/companion/devices/${path(deviceId)}`, { alias }) }
export async function updateAdminDeviceMode(deviceId: string, mode: CompanionMode) { await http.put(`/admin/companion/devices/${path(deviceId)}/mode`, { mode }) }
export async function unbindAdminDevice(deviceId: string) { await http.delete(`/admin/companion/devices/${path(deviceId)}`) }
export async function listTemplates(query = '', page = 1, limit = 20, options?: Options): Promise<PageResult<AdminTemplate>> {
  const data = pageData(await getData('/agent/template/page', { agentName: query, page, limit }, options))
  return { total: data.total, list: data.list.map((item) => { if (!record(item)) throw new ApiProtocolError('模板数据格式错误', item); return { id: id(item.id), agentCode: text(item.agentCode), agentName: text(item.agentName), description: text(item.description || item.introduction), systemPrompt: text(item.systemPrompt), sort: number(item.sort), raw: item } }) }
}
export async function deleteTemplate(templateId: string) { await http.delete(`/admin/companion/templates/${path(templateId)}`) }
export async function createTemplate(input: TemplateInput) { await http.post('/admin/companion/templates', input) }
export async function updateTemplate(templateId: string, input: TemplateInput) { await http.put(`/admin/companion/templates/${path(templateId)}`, input) }
export async function listModels(modelType: string, query = '', page = 1, limit = 20, options?: Options): Promise<PageResult<AdminResource>> {
  const data = pageData(await getData('/admin/companion/resources/models', { modelType, keyword: query, page, limit }, options))
  return { total: data.total, list: data.list.map(safeModel) }
}
export async function setModelEnabled(modelId: string, enabled: boolean) { await http.put(`/admin/companion/models/${path(modelId)}/enabled/${enabled ? 1 : 0}`) }
export async function setDefaultModel(modelId: string) { await http.put(`/admin/companion/resources/models/${path(modelId)}/default`) }
export async function createModel(input: ModelCreateInput) { await http.post('/admin/companion/resources/models', input) }
export async function updateModel(modelId: string, input: ModelUpdateInput) { await http.put(`/admin/companion/resources/models/${path(modelId)}`, input) }
export async function deleteModel(modelId: string) { await http.delete(`/admin/companion/resources/models/${path(modelId)}`) }
export async function listProviders(modelType: string, options?: Options): Promise<Array<{ code: string; name: string }>> {
  const data = await getData('/admin/companion/resources/providers', { modelType }, options)
  if (!Array.isArray(data)) throw new ApiProtocolError('供应器数据格式错误', data)
  return data.map((item) => { if (!record(item)) throw new ApiProtocolError('供应器数据格式错误', item); return { code: text(item.code), name: text(item.name) } })
}
export async function listVoiceResources(query = '', page = 1, limit = 20, options?: Options): Promise<PageResult<AdminResource>> {
  const data = pageData(await getData('/voiceResource', { name: query, page, limit }, options)); return { total: data.total, list: data.list.map(voiceResource) }
}
export async function deleteVoiceResource(resourceId: string) { await http.delete(`/admin/companion/voice-resources/${path(resourceId)}`) }
export async function listTimbres(ttsModelId: string, query = '', page = 1, limit = 20, options?: Options): Promise<PageResult<TimbreResource>> {
  if (!ttsModelId) return { list: [], total: 0 }
  const data = pageData(await getData('/admin/companion/resources/timbres', { ttsModelId, keyword: query, page, limit }, options))
  return { total: data.total, list: data.list.map(timbre) }
}
export async function deleteTimbre(timbreId: string) { await http.delete(`/admin/companion/timbres/${path(timbreId)}`) }
export async function createTimbre(input: TimbreInput) { await http.post('/admin/companion/resources/timbres', input) }
export async function updateTimbre(timbreId: string, input: TimbreInput) { await http.put(`/admin/companion/resources/timbres/${path(timbreId)}`, input) }
export async function listTtsModelOptions(options?: Options): Promise<AdminResource[]> {
  const data = await getData('/admin/companion/resources/models/options', { modelType: 'TTS' }, options)
  if (!Array.isArray(data)) throw new ApiProtocolError('模型选项格式错误', data)
  return data.map(safeModel)
}
export async function getSystemSettings(options?: Options): Promise<SystemSettings> {
  return systemSettings(await getData('/admin/companion/system-settings', {}, options))
}
export async function saveSystemSettings(input: SystemSettingsInput): Promise<SystemSettings> {
  const response = await http.put<ApiResult<unknown>>('/admin/companion/system-settings', input)
  return systemSettings(unwrap(response.data, response.config))
}
export async function listFirmware(query = '', page = 1, limit = 20, options?: Options): Promise<PageResult<Firmware>> {
  const data = pageData(await getData('/otaMag', { firmwareName: query, page, limit }, options))
  return { total: data.total, list: data.list.map((item) => { if (!record(item)) throw new ApiProtocolError('固件数据格式错误', item); return { id: id(item.id), firmwareName: text(item.firmwareName), version: text(item.version), type: text(item.type), remark: text(item.remark), firmwarePath: text(item.firmwarePath) } }) }
}
export async function deleteFirmware(firmwareId: string) { await http.delete(`/admin/companion/firmware/${path(firmwareId)}`) }
export async function updateFirmware(firmwareId: string, input: FirmwareInput) { await http.put(`/admin/companion/firmware/${path(firmwareId)}`, input) }
export async function uploadFirmware(file: File, input: FirmwareInput) { const body = new FormData(); body.append('file', file); body.append('firmwareName', input.firmwareName); body.append('type', input.type); body.append('version', input.version); if (input.remark) body.append('remark', input.remark); await http.post('/admin/companion/firmware/upload', body, { headers: { 'Content-Type': 'multipart/form-data' } }) }
export async function listPlans(query = '', page = 1, limit = 20, options?: Options): Promise<PageResult<CompanionPlan>> {
  const data = pageData(await getData('/admin/companion/plans/page', { keyword: query, page, limit }, options))
  return { total: data.total, list: data.list.map((item) => { if (!record(item)) throw new ApiProtocolError('套餐数据格式错误', item); return { id: id(item.id), planCode: text(item.planCode), planName: text(item.planName), maxDevices: number(item.maxDevices), maxProfiles: number(item.maxProfiles), longTermMemory: number(item.longTermMemory), advancedVoice: number(item.advancedVoice), status: number(item.status) } }) }
}
export async function createPlan(input: PlanInput) { const response = await http.post<ApiResult<unknown>>('/admin/companion/plans', input); return id(unwrap(response.data, response.config)) }
export async function updatePlan(planId: string, input: PlanInput) { await http.put(`/admin/companion/plans/${path(planId)}`, input) }
export async function grantSubscription(userId: string, planId: string, expiresAt: string) { await http.put(`/admin/companion/subscriptions/${path(userId)}`, { planId, expiresAt }) }
export async function pauseSubscription(userId: string) { await http.put(`/admin/companion/subscriptions/${path(userId)}/pause`) }
export async function cancelSubscription(userId: string) { await http.put(`/admin/companion/subscriptions/${path(userId)}/cancel`) }
export async function listAudit(query = '', page = 1, limit = 20, options?: Options): Promise<PageResult<AuditRow>> {
  const data = pageData(await getData('/admin/companion/audit', { keyword: query, page, limit }, options))
  return { total: data.total, list: data.list.map((item) => { if (!record(item)) throw new ApiProtocolError('审计数据格式错误', item); return { id: id(item.id), operatorId: number(item.operatorId), targetUserId: item.targetUserId == null ? null : number(item.targetUserId), action: text(item.action), resourceType: text(item.resourceType), resourceId: item.resourceId == null ? null : text(item.resourceId), summary: text(item.summary), createdAt: text(item.createdAt) } }) }
}
export async function deletePlan(planId: string) { await http.delete(`/admin/companion/plans/${path(planId)}`) }
function safeModel(item: unknown): AdminResource { if (!record(item)) throw new ApiProtocolError('安全模型数据格式错误', item); const profileUsageCount = number(item.profileUsageCount), deviceUsageCount = number(item.deviceUsageCount); if (!Number.isSafeInteger(profileUsageCount) || profileUsageCount < 0 || !Number.isSafeInteger(deviceUsageCount) || deviceUsageCount < 0) throw new ApiProtocolError('模型使用量格式错误', item); return { id: id(item.id), name: text(item.name), type: text(item.type), modelCode: text(item.modelCode), providerCode: text(item.providerCode), enabled: number(item.enabled) === 1, isDefault: number(item.isDefault) === 1, docLink: text(item.docLink), remark: text(item.remark), sort: number(item.sort), profileUsageCount, deviceUsageCount } }
function timbre(item: unknown): TimbreResource { if (!record(item)) throw new ApiProtocolError('音色数据格式错误', item); return { id: id(item.id), name: text(item.name), languages: text(item.languages), ttsModelId: text(item.ttsModelId), ttsVoice: text(item.ttsVoice), remark: text(item.remark), sort: number(item.sort), voiceDemo: text(item.voiceDemo) } }
function voiceResource(item: unknown): AdminResource { if (!record(item)) throw new ApiProtocolError('克隆音色数据格式错误', item); return { id: id(item.id), name: text(item.name), type: 'voiceClone', enabled: number(item.trainStatus) === 2, isDefault: false, remark: text(item.trainError) } }
function settingOption(item: unknown): SystemSettingOption {
  if (!record(item) || typeof item.id !== 'string' || !item.id || typeof item.name !== 'string' || typeof item.type !== 'string') {
    throw new ApiProtocolError('系统设置资源选项格式错误', item)
  }
  return { id: item.id, name: item.name, type: item.type }
}
function serviceHealth(item: unknown): ServiceHealth {
  if (!record(item) || !['available', 'unavailable', 'unknown'].includes(String(item.status))
    || typeof item.address !== 'string' || !item.address || typeof item.checkedAt !== 'string' || !item.checkedAt) {
    throw new ApiProtocolError('系统设置健康状态格式错误', item)
  }
  return { status: item.status as HealthStatus, address: item.address, checkedAt: item.checkedAt }
}
function systemSettings(item: unknown): SystemSettings {
  if (!record(item) || typeof item.publicWebsocketUrl !== 'string' || typeof item.publicOtaUrl !== 'string'
    || typeof item.xiaozhiListenHost !== 'string' || typeof item.xiaozhiListenPort !== 'number' || !Number.isFinite(item.xiaozhiListenPort)
    || typeof item.otaListenHost !== 'string' || typeof item.otaListenPort !== 'number' || !Number.isFinite(item.otaListenPort)
    || typeof item.defaultLlmModelId !== 'string' || typeof item.defaultTtsModelId !== 'string'
    || typeof item.defaultAsrModelId !== 'string' || typeof item.defaultVadModelId !== 'string'
    || !(item.defaultTtsVoiceId == null || typeof item.defaultTtsVoiceId === 'string')
    || !(item.proactivePlannerPrompt == null || typeof item.proactivePlannerPrompt === 'string')
    || !record(item.modelOptions) || !Array.isArray(item.voices)
    || !record(item.health) || typeof item.restartRequired !== 'boolean' || !Array.isArray(item.restartServices)
    || item.restartServices.some((value) => typeof value !== 'string')) {
    throw new ApiProtocolError('系统设置数据格式错误', item)
  }
  const modelOptions = Object.fromEntries(Object.entries(item.modelOptions).map(([type, options]) => {
    if (!Array.isArray(options)) throw new ApiProtocolError('系统设置模型选项格式错误', options)
    return [type, options.map(settingOption)]
  }))
  return {
    publicWebsocketUrl: item.publicWebsocketUrl,
    publicOtaUrl: item.publicOtaUrl,
    xiaozhiListenHost: item.xiaozhiListenHost,
    xiaozhiListenPort: item.xiaozhiListenPort,
    otaListenHost: item.otaListenHost,
    otaListenPort: item.otaListenPort,
    defaultLlmModelId: item.defaultLlmModelId,
    defaultVllmModelId: text(item.defaultVllmModelId) || undefined,
    defaultTtsModelId: item.defaultTtsModelId,
    defaultAsrModelId: item.defaultAsrModelId,
    defaultVadModelId: item.defaultVadModelId,
    defaultMemoryModelId: text(item.defaultMemoryModelId) || undefined,
    defaultTtsVoiceId: text(item.defaultTtsVoiceId) || undefined,
    proactivePlannerPrompt: text(item.proactivePlannerPrompt),
    modelOptions,
    voices: item.voices.map(settingOption),
    health: { xiaozhi: serviceHealth(item.health.xiaozhi), ota: serviceHealth(item.health.ota) },
    restartRequired: item.restartRequired,
    restartServices: [...item.restartServices] as string[],
  }
}
function adminMemory(item: unknown): CompanionMemory {
  if (!record(item) || typeof item.id !== 'string' || !item.id || typeof item.content !== 'string'
    || typeof item.updated_at !== 'string') throw new ApiProtocolError('记忆数据字段错误', item)
  const nullable = (value: unknown) => value == null || typeof value === 'string'
  if (!nullable(item.source_device_id) || !nullable(item.source_profile_id)
    || !nullable(item.sourceDeviceName) || !nullable(item.sourceProfileName)) throw new ApiProtocolError('记忆数据字段错误', item)
  return {
    id: item.id, content: item.content, updatedAt: item.updated_at,
    sourceDeviceId: item.source_device_id ?? null, sourceProfileId: item.source_profile_id ?? null,
    sourceDeviceName: item.sourceDeviceName ?? null, sourceProfileName: item.sourceProfileName ?? null,
  }
}
