import type { AxiosResponse } from 'axios'

import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'
import { modelTypes, type CredentialStatus, type ModelType } from './models'

export interface CompanionProfile {
  id: string
  name: string
  relationMode: 'friend' | 'lover'
  userAddress: string
  personality: string
  systemPrompt: string
  screenExpressionEnabled: boolean
  cameraPreferenceEnabled: boolean
  templateId: string | null
  llmModelId: string | null
  llmModelName: string | null
  ttsModelId: string | null
  ttsModelName: string | null
  ttsVoiceId: string | null
  ttsVoiceName: string | null
  ttsLanguage: string | null
  createdAt: string | null
  updatedAt: string | null
  models: ProfileModelBinding[]
  effectiveModels: EffectiveProfileModel[]
  activeVersionNo?: number | null
  boundDevices?: CompanionBoundDevice[]
  memoryPolicy?: Record<string, unknown>
  skills?: CompanionSkillBinding[]
}

export interface CompanionBoundDevice {
  id: string
  alias: string | null
  macAddress: string
  board: string | null
  appVersion: string | null
  online: boolean
}

export interface CompanionSkillBinding {
  skillId: string
  versionMode: string | null
  fixedVersion: number | null
  overrideJson: string | null
  triggerPriority: number | null
  enabled: boolean
}

export type ProfileSkillBindingInput = CompanionSkillBinding

export type ProfileModelSource = 'default' | 'global' | 'private'
export interface ProfileModelBinding {
  modelType: ModelType
  source: ProfileModelSource
  resourceId?: string
  name?: string | null
  overrides?: Record<string, unknown>
  enabled?: boolean
  unavailableReason?: string | null
}
export interface ProfileModelOption {
  id: string
  modelType: ModelType
  name: string
  source: 'global' | 'private'
  providerCode: string
  enabled: boolean
  isDefault: boolean
  vendorName: string | null
  protocol: string | null
  credentialStatus: CredentialStatus
  unavailableReason: string | null
}
export interface EffectiveProfileModel {
  modelType: ModelType
  resourceId: string | null
  name: string
  source: ProfileModelSource | 'legacy'
  modelId: string | null
  overridden: boolean
  overrides: Record<string, unknown>
  enabled: boolean
  unavailableReason: string | null
}

export interface ProfileVersion {
  id: string
  versionNo: number
  source: string
  createdAt: string
}

export interface ProfileVersionDetail extends ProfileVersion {
  snapshot: {
    name: string
    relationMode: 'friend' | 'lover' | null
    userAddress: string | null
    personality: string | null
    systemPrompt: string
    screenExpressionEnabled: boolean | null
    cameraPreferenceEnabled: boolean | null
    ttsVoiceId: string | null
    modelResourceIds: Record<ModelType, string | null>
  }
}

export interface CompanionTemplate {
  id: string
  code: string
  name: string
  relationMode: 'friend' | 'lover'
}

export interface VoiceOption {
  id: string
  name: string
  voiceDemo?: string | null
  languages: string | null
  isClone: boolean
}

export interface ProfileUpdateInput {
  name: string
  relationMode: 'friend' | 'lover'
  userAddress: string
  personality: string
  systemPrompt: string
  ttsVoiceId?: string
  screenExpressionEnabled: boolean
  cameraPreferenceEnabled: boolean
  models?: ProfileModelBinding[]
  skills?: ProfileSkillBindingInput[]
}

interface RequestOptions { signal?: AbortSignal }

export const emptyProfile: CompanionProfile = {
  id: '', name: '', relationMode: 'friend', userAddress: '', personality: '', systemPrompt: '',
  screenExpressionEnabled: true, cameraPreferenceEnabled: true, templateId: null,
  llmModelId: null, llmModelName: null, ttsModelId: null, ttsModelName: null,
  ttsVoiceId: null, ttsVoiceName: null, ttsLanguage: null, createdAt: null, updatedAt: null,
  models: [], effectiveModels: [], boundDevices: [], memoryPolicy: {
    scope: 'device', namespace: 'user-agent-device', summaryMemorySource: 'device-namespace',
  }, skills: [],
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

function idString(value: unknown) {
  return typeof value === 'string' && value.length > 0
    ? value
    : typeof value === 'number' && Number.isSafeInteger(value) ? String(value) : null
}

function optionalString(value: unknown) {
  return value === null || value === undefined || typeof value === 'string'
}

function nullableString(value: unknown) {
  return typeof value === 'string' ? value : null
}

function parseModelType(value: unknown, response: AxiosResponse) {
  if (!modelTypes.includes(value as ModelType)) throw new ApiProtocolError('模型类型字段错误', value, response.config)
  return value as ModelType
}

function parseOverrides(value: unknown, response: AxiosResponse) {
  if (value === null || value === undefined) return {}
  if (!isRecord(value)) throw new ApiProtocolError('角色模型参数格式错误', value, response.config)
  return value
}

function parseBindings(value: unknown, response: AxiosResponse): ProfileModelBinding[] {
  if (value === null || value === undefined) return []
  if (!Array.isArray(value)) throw new ApiProtocolError('角色模型绑定格式错误', value, response.config)
  return value.map((item) => {
    if (!isRecord(item) || !['default', 'global', 'private'].includes(String(item.source)) || !optionalString(item.resourceId)
      || !optionalString(item.name) || typeof item.enabled !== 'boolean' || !optionalString(item.unavailableReason)) {
      throw new ApiProtocolError('角色模型绑定字段错误', item, response.config)
    }
    return {
      modelType: parseModelType(item.modelType, response), source: item.source as ProfileModelSource,
      resourceId: item.resourceId || undefined, name: item.name ?? null, overrides: parseOverrides(item.overrides, response),
      enabled: item.enabled, unavailableReason: item.unavailableReason ?? null,
    }
  })
}

function parseEffectiveModels(value: unknown, response: AxiosResponse): EffectiveProfileModel[] {
  if (value === null || value === undefined) return []
  if (!Array.isArray(value)) throw new ApiProtocolError('生效模型配置格式错误', value, response.config)
  return value.map((item) => {
    if (!isRecord(item) || !optionalString(item.resourceId) || typeof item.name !== 'string'
      || !['default', 'global', 'private', 'legacy'].includes(String(item.source)) || !optionalString(item.modelId)
      || typeof item.overridden !== 'boolean' || typeof item.enabled !== 'boolean'
      || !optionalString(item.unavailableReason)) throw new ApiProtocolError('生效模型配置字段错误', item, response.config)
    return { modelType: parseModelType(item.modelType, response), resourceId: item.resourceId ?? null, name: item.name,
      source: item.source as EffectiveProfileModel['source'], modelId: item.modelId ?? null,
      overridden: item.overridden, overrides: parseOverrides(item.overrides, response), enabled: item.enabled,
      unavailableReason: item.unavailableReason ?? null }
  })
}

function requestConfig(options?: RequestOptions) { return options?.signal ? { signal: options.signal } : undefined }
function encoded(value: string) { return encodeURIComponent(value) }

function unwrap(response: AxiosResponse<ApiResult<unknown>>) {
  const result: unknown = response.data
  if (!isRecord(result) || typeof result.code !== 'number' || typeof result.msg !== 'string' || !('data' in result)) {
    throw new ApiProtocolError('响应数据格式错误', result, response.config)
  }
  if (result.code !== 0) throw new ApiError(result.code, result.msg || '请求失败', result.data, response.config)
  return result.data
}

function parseProfile(value: unknown, response: AxiosResponse): CompanionProfile {
  if (!isRecord(value)) throw new ApiProtocolError('陪伴角色数据格式错误', value, response.config)
  const id = idString(value.id)
  if (!id || typeof value.name !== 'string' || (value.relationMode !== 'friend' && value.relationMode !== 'lover')
    || !optionalString(value.userAddress) || !optionalString(value.personality) || !optionalString(value.systemPrompt)
    || (value.screenExpressionEnabled !== 0 && value.screenExpressionEnabled !== 1)
    || (value.cameraPreferenceEnabled !== 0 && value.cameraPreferenceEnabled !== 1)
    || !optionalString(value.templateId) || !optionalString(value.llmModelId) || !optionalString(value.llmModelName)
    || !optionalString(value.ttsModelId) || !optionalString(value.ttsModelName) || !optionalString(value.ttsVoiceId)
    || !optionalString(value.ttsVoiceName) || !optionalString(value.ttsLanguage)
    || !optionalString(value.createdAt) || !optionalString(value.updatedAt)
    || (value.activeVersionNo !== null && value.activeVersionNo !== undefined
      && (typeof value.activeVersionNo !== 'number' || !Number.isInteger(value.activeVersionNo) || value.activeVersionNo < 1))) {
    throw new ApiProtocolError('陪伴角色数据字段错误', value, response.config)
  }
  return {
    id, name: value.name, relationMode: value.relationMode, userAddress: value.userAddress ?? '',
    personality: value.personality ?? '', systemPrompt: value.systemPrompt ?? '',
    screenExpressionEnabled: value.screenExpressionEnabled === 1, cameraPreferenceEnabled: value.cameraPreferenceEnabled === 1,
    templateId: value.templateId ?? null, llmModelId: value.llmModelId ?? null, llmModelName: value.llmModelName ?? null,
    ttsModelId: value.ttsModelId ?? null, ttsModelName: value.ttsModelName ?? null, ttsVoiceId: value.ttsVoiceId ?? null,
    ttsVoiceName: value.ttsVoiceName ?? null, ttsLanguage: value.ttsLanguage ?? null,
    createdAt: value.createdAt ?? null, updatedAt: value.updatedAt ?? null,
    models: parseBindings(value.models, response), effectiveModels: parseEffectiveModels(value.effectiveModels, response),
    activeVersionNo: typeof value.activeVersionNo === 'number' ? value.activeVersionNo : null,
    boundDevices: parseBoundDevices(value.boundDevices, response),
    memoryPolicy: value.memoryPolicy && isRecord(value.memoryPolicy) ? value.memoryPolicy : {
      scope: 'device', namespace: 'user-agent-device', summaryMemorySource: 'device-namespace',
    },
    skills: parseSkillBindings(value.skills, response),
  }
}

function parseBoundDevices(value: unknown, response: AxiosResponse): CompanionBoundDevice[] {
  if (value === undefined || value === null) return []
  if (!Array.isArray(value)) throw new ApiProtocolError('绑定设备数据格式错误', value, response.config)
  return value.map((item) => {
    if (!isRecord(item) || !idString(item.id) || !optionalString(item.alias) || typeof item.macAddress !== 'string'
      || !item.macAddress || !optionalString(item.board) || !optionalString(item.appVersion) || typeof item.online !== 'boolean') {
      throw new ApiProtocolError('绑定设备字段错误', item, response.config)
    }
    return { id: idString(item.id)!, alias: item.alias ?? null, macAddress: item.macAddress,
      board: item.board ?? null, appVersion: item.appVersion ?? null, online: item.online }
  })
}

function parseSkillBindings(value: unknown, response: AxiosResponse): CompanionSkillBinding[] {
  if (value === undefined || value === null) return []
  if (!Array.isArray(value)) throw new ApiProtocolError('角色 Skill 配置格式错误', value, response.config)
  return value.map((item) => {
    if (!isRecord(item) || typeof item.skillId !== 'string' || !item.skillId
      || !optionalString(item.versionMode) || (item.fixedVersion !== null && item.fixedVersion !== undefined
        && (typeof item.fixedVersion !== 'number' || !Number.isInteger(item.fixedVersion)))
      || !optionalString(item.overrideJson) || (item.triggerPriority !== null && item.triggerPriority !== undefined
        && typeof item.triggerPriority !== 'number') || typeof item.enabled !== 'boolean') {
      throw new ApiProtocolError('角色 Skill 配置字段错误', item, response.config)
    }
    return { skillId: item.skillId, versionMode: item.versionMode ?? null, fixedVersion: item.fixedVersion ?? null,
      overrideJson: item.overrideJson ?? null, triggerPriority: item.triggerPriority ?? null, enabled: item.enabled }
  })
}

export async function listProfiles(options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/companion/profiles', requestConfig(options))
  const data = unwrap(response)
  if (!Array.isArray(data)) throw new ApiProtocolError('陪伴角色列表数据格式错误', data, response.config)
  return data.map((item) => parseProfile(item, response))
}

export async function listTemplates(options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>('/companion/templates', requestConfig(options))
  const data = unwrap(response)
  if (!Array.isArray(data)) throw new ApiProtocolError('陪伴角色模板列表数据格式错误', data, response.config)
  return data.map((item): CompanionTemplate => {
    if (!isRecord(item) || !idString(item.id) || typeof item.code !== 'string' || !item.code
      || typeof item.name !== 'string' || !item.name
      || (item.relationMode !== 'friend' && item.relationMode !== 'lover')) {
      throw new ApiProtocolError('陪伴角色模板数据字段错误', item, response.config)
    }
    return { id: idString(item.id)!, code: item.code, name: item.name, relationMode: item.relationMode }
  })
}

export async function listVoices(ttsModelId: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/models/${encoded(ttsModelId)}/voices`, requestConfig(options))
  const data = unwrap(response)
  if (data === null) return []
  if (!Array.isArray(data)) throw new ApiProtocolError('声音列表数据格式错误', data, response.config)
  return data.map((item): VoiceOption => {
    if (!isRecord(item) || !idString(item.id) || typeof item.name !== 'string' || !item.name
      || !optionalString(item.voiceDemo) || !optionalString(item.languages) || typeof item.isClone !== 'boolean') {
      throw new ApiProtocolError('声音数据字段错误', item, response.config)
    }
    return {
      id: idString(item.id)!, name: item.name, voiceDemo: item.voiceDemo ?? null,
      languages: item.languages ?? null, isClone: item.isClone,
    }
  })
}

export async function getProfile(id: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/companion/profiles/${encoded(id)}`, requestConfig(options))
  return parseProfile(unwrap(response), response)
}

export async function createProfile(templateId: string, name: string, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>('/companion/profiles', { templateId, name }, requestConfig(options))
  const id = idString(unwrap(response))
  if (!id) throw new ApiProtocolError('新角色编号格式错误', response.data, response.config)
  return id
}

export async function updateProfile(id: string, input: ProfileUpdateInput, options?: RequestOptions) {
  const payload: Record<string, unknown> = {
    agentName: input.name, relationMode: input.relationMode, userAddress: input.userAddress,
    personality: input.personality, systemPrompt: input.systemPrompt,
    screenExpressionEnabled: input.screenExpressionEnabled ? 1 : 0,
    cameraPreferenceEnabled: input.cameraPreferenceEnabled ? 1 : 0,
  }
  if (input.ttsVoiceId !== undefined) payload.ttsVoiceId = input.ttsVoiceId
  if (input.models) {
    if (input.models.some((model) => model.source === 'private')) throw new Error('请先替换所有已停用的个人模型')
    const models: Array<{ modelType: ModelType; source: 'default' | 'global'; resourceId?: string }> = []
    for (const model of input.models) {
      if (model.source === 'default') models.push({ modelType: model.modelType, source: model.source })
      if (model.source === 'global' && model.resourceId) models.push({ modelType: model.modelType, source: model.source, resourceId: model.resourceId })
    }
    payload.models = models
  }
  if (input.skills) payload.skills = input.skills.map((skill) => ({
    skillId: skill.skillId, versionMode: skill.versionMode || 'LATEST', fixedVersion: skill.fixedVersion,
    overrideJson: skill.overrideJson, triggerPriority: skill.triggerPriority ?? 0, enabled: skill.enabled,
  }))
  unwrap(await http.put<ApiResult<unknown>>(`/companion/profiles/${encoded(id)}`, payload, requestConfig(options)))
}

export async function listProfileModelOptions(id: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/companion/profiles/${encoded(id)}/model-options`, requestConfig(options))
  const data = unwrap(response)
  if (!Array.isArray(data)) throw new ApiProtocolError('角色模型选项格式错误', data, response.config)
  const parsed = data.map((item): ProfileModelOption => {
    if (!isRecord(item) || !idString(item.id) || typeof item.name !== 'string' || !item.name
      || (item.source !== 'global' && item.source !== 'private') || typeof item.providerCode !== 'string'
      || !item.providerCode || typeof item.enabled !== 'boolean' || typeof item.isDefault !== 'boolean'
      || !optionalString(item.vendorName)
      || !optionalString(item.protocol) || !['configured', 'missing', 'not_required', 'unknown'].includes(String(item.credentialStatus))
      || !optionalString(item.unavailableReason)) throw new ApiProtocolError('角色模型选项字段错误', item, response.config)
    return {
      id: idString(item.id)!, modelType: parseModelType(item.modelType, response), name: item.name,
      source: item.source, providerCode: item.providerCode, enabled: item.enabled, isDefault: item.isDefault,
      vendorName: item.vendorName ?? null, protocol: item.protocol ?? null,
      credentialStatus: item.credentialStatus as CredentialStatus, unavailableReason: item.unavailableReason ?? null,
    }
  })
  for (const modelType of modelTypes) {
    const defaults = parsed.filter((item) => item.modelType === modelType && item.source === 'global'
      && item.enabled && item.isDefault)
    if (defaults.length > 1) throw new ApiProtocolError('角色默认模型配置不唯一', data, response.config)
  }
  return parsed
}

export async function restorePrompt(id: string, options?: RequestOptions) {
  unwrap(await http.post<ApiResult<unknown>>(`/companion/profiles/${encoded(id)}/restore-prompt`, undefined, requestConfig(options)))
}

export async function activateProfileVersion(id: string, snapshotId: string, options?: RequestOptions) {
  unwrap(await http.post<ApiResult<unknown>>(`/agent/${encoded(id)}/snapshots/${encoded(snapshotId)}/activate`, undefined, requestConfig(options)))
}

export async function publishProfileVersion(id: string, options?: RequestOptions) {
  unwrap(await http.post<ApiResult<unknown>>(`/agent/${encoded(id)}/snapshots/publish`, undefined, requestConfig(options)))
}

export async function deleteProfile(id: string, options?: RequestOptions) {
  unwrap(await http.delete<ApiResult<unknown>>(`/companion/profiles/${encoded(id)}`, requestConfig(options)))
}

export async function listProfileVersions(id: string, page = 1, limit = 10, maxVersionNo?: number, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`/agent/${encoded(id)}/snapshots`, { params: { page, limit, maxVersionNo }, ...requestConfig(options) })
  const data = unwrap(response)
  if (!isRecord(data) || typeof data.total !== 'number' || !Number.isInteger(data.total) || data.total < 0 || !Array.isArray(data.list)) throw new ApiProtocolError('版本列表数据格式错误', data, response.config)
  const list = data.list.map((item): ProfileVersion => {
    if (!isRecord(item) || !idString(item.id) || typeof item.versionNo !== 'number' || !Number.isInteger(item.versionNo)
      || typeof item.source !== 'string' || typeof item.createdAt !== 'string') throw new ApiProtocolError('版本数据字段错误', item, response.config)
    return { id: idString(item.id)!, versionNo: item.versionNo, source: item.source, createdAt: item.createdAt }
  })
  return { total: data.total, list }
}

export async function getProfileVersion(id: string, snapshotId: string, options?: RequestOptions): Promise<ProfileVersionDetail> {
  const response = await http.get<ApiResult<unknown>>(`/agent/${encoded(id)}/snapshots/${encoded(snapshotId)}`, requestConfig(options))
  const data = unwrap(response)
  if (!isRecord(data) || !idString(data.id) || typeof data.versionNo !== 'number' || !Number.isInteger(data.versionNo)
    || typeof data.source !== 'string' || typeof data.createdAt !== 'string' || !isRecord(data.snapshotData)) {
    throw new ApiProtocolError('版本详情数据格式错误', data, response.config)
  }
  const snapshot = data.snapshotData
  if (typeof snapshot.agentName !== 'string'
    || (snapshot.relationMode !== null && snapshot.relationMode !== undefined
      && snapshot.relationMode !== 'friend' && snapshot.relationMode !== 'lover')
    || !optionalString(snapshot.userAddress) || !optionalString(snapshot.personality) || !optionalString(snapshot.systemPrompt)
    || (snapshot.screenExpressionEnabled !== null && snapshot.screenExpressionEnabled !== undefined
      && snapshot.screenExpressionEnabled !== 0 && snapshot.screenExpressionEnabled !== 1)
    || (snapshot.cameraPreferenceEnabled !== null && snapshot.cameraPreferenceEnabled !== undefined
      && snapshot.cameraPreferenceEnabled !== 0 && snapshot.cameraPreferenceEnabled !== 1)
    || !optionalString(snapshot.ttsVoiceId)
    || modelTypes.some((modelType) => !optionalString(snapshot[modelType === 'Memory' ? 'memModelId' : `${modelType.toLowerCase()}ModelId`]))) {
    throw new ApiProtocolError('版本快照字段错误', snapshot, response.config)
  }
  return {
    id: idString(data.id)!, versionNo: data.versionNo, source: data.source, createdAt: data.createdAt,
    snapshot: {
      name: snapshot.agentName,
      relationMode: snapshot.relationMode === 'friend' || snapshot.relationMode === 'lover' ? snapshot.relationMode : null,
      userAddress: nullableString(snapshot.userAddress), personality: nullableString(snapshot.personality),
      systemPrompt: nullableString(snapshot.systemPrompt) ?? '',
      screenExpressionEnabled: snapshot.screenExpressionEnabled === null || snapshot.screenExpressionEnabled === undefined
        ? null : snapshot.screenExpressionEnabled === 1,
      cameraPreferenceEnabled: snapshot.cameraPreferenceEnabled === null || snapshot.cameraPreferenceEnabled === undefined
        ? null : snapshot.cameraPreferenceEnabled === 1,
      ttsVoiceId: nullableString(snapshot.ttsVoiceId),
      modelResourceIds: {
        LLM: nullableString(snapshot.llmModelId), ASR: nullableString(snapshot.asrModelId), TTS: nullableString(snapshot.ttsModelId),
        VAD: nullableString(snapshot.vadModelId), VLLM: nullableString(snapshot.vllmModelId), Memory: nullableString(snapshot.memModelId),
      },
    },
  } satisfies ProfileVersionDetail
}
