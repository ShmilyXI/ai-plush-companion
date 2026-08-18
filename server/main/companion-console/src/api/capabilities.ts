import type { AxiosResponse } from 'axios'

import { ApiProtocolError } from './devices'
import http, { ApiError, type ApiResult } from './http'

export type CapabilityType = 'SKILL' | 'PLUGIN' | 'MCP_SERVER'
export type CapabilityStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED'
export type SkillTriggerType = 'KEYWORD' | 'REGEX' | 'POSITIVE_EXAMPLE' | 'NEGATIVE_EXAMPLE'
export type SkillToolType = 'PLUGIN' | 'MCP' | 'DEVICE_TOOL'
export type SkillResponseMode = 'LLM' | 'FIXED'
export type DeviceSkillVersionMode = 'LATEST' | 'FIXED'
export type McpTransport = 'STDIO' | 'SSE' | 'STREAMABLE_HTTP'
export type McpToolStatus = 'DISCOVERED' | 'ACTIVE' | 'DRIFTED' | 'MISSING'

export interface SkillTrigger {
  type: SkillTriggerType
  value: string
  priority: number
  caseSensitive: boolean
  enabled: boolean
}

export interface SkillTool {
  toolType: SkillToolType
  toolRefId: string
  toolName: string
  alias: string | null
  purpose: string | null
  defaultParams: Record<string, unknown>
  required: boolean
  sortOrder: number
}

export interface PluginDefinition {
  executorName: string
  inputSchema: Record<string, unknown>
  configSchema: Record<string, unknown>
  secretFields: string[]
  defaultConfig: Record<string, unknown>
}

export interface McpDefinition {
  transport: McpTransport
  connectionConfig: Record<string, unknown>
  secretRefs: Record<string, string>
  approvedCommandTemplate: Record<string, unknown> | null
  healthStatus: 'UNKNOWN' | 'HEALTHY' | 'UNHEALTHY' | null
  lastError: string | null
  lastCheckedAt: string | null
}

export type McpDefinitionInput = Omit<McpDefinition, 'healthStatus' | 'lastError' | 'lastCheckedAt'>

export interface Capability {
  id: string
  type: CapabilityType
  name: string
  description: string | null
  status: CapabilityStatus
  draftVersion: number
  publishedVersion: number | null
  executionPrompt: string | null
  semanticThreshold: number | null
  responseMode: SkillResponseMode | null
  timeoutMs: number | null
  failureMessage: string | null
  triggers: SkillTrigger[]
  tools: SkillTool[]
  plugin: PluginDefinition | null
  mcp: McpDefinition | null
  createdAt: string | null
  updatedAt: string | null
  packageVersion?: number | null
  packageSha256?: string | null
  packageSource?: string | null
  packageValidationStatus?: string | null
}

export interface CapabilityPage {
  total: number
  list: Capability[]
}

export interface CapabilitySaveInput {
  type: CapabilityType
  name: string
  description?: string | null
  executionPrompt?: string | null
  semanticThreshold?: number | null
  responseMode?: SkillResponseMode | null
  timeoutMs?: number | null
  failureMessage?: string | null
  triggers?: SkillTrigger[]
  tools?: SkillTool[]
  plugin?: PluginDefinition | null
  mcp?: McpDefinitionInput | null
}

export interface DeviceSkillBindingInput {
  skillId: string
  versionMode: DeviceSkillVersionMode
  fixedVersion?: number | null
  enabled?: boolean
  overrides?: Record<string, unknown>
  triggerPriority?: number
}

export interface DeviceSkillBinding {
  skillId: string
  skillName: string | null
  versionMode: DeviceSkillVersionMode
  fixedVersion: number | null
  resolvedVersion: number
  enabled: boolean
  overrides: Record<string, unknown>
  triggerPriority: number
  configVersion: number
}

export interface DeviceSkillCatalogItem {
  skillId: string
  name: string
  description: string | null
  publishedVersion: number
  versions: number[]
  overridableFields: string[]
  defaults: Record<string, unknown>
  available: boolean
  unavailableReason: string | null
}

export interface McpToolSnapshot {
  id: string
  mcpServerId: string
  toolName: string
  inputSchemaJson: string
  schemaSha256: string
  status: McpToolStatus
  approved: 0 | 1
  syncedAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface CapabilityRoutePreview {
  deterministicMatches: string[]
  semanticRequired: boolean
  eligibleSkillIds: string[]
  selectedSkillId: string | null
  allowedTools: string[]
}

export interface McpLocalConfigImportResult {
  imported: string[]
  skipped: string[]
}

export interface PluginExecutor {
  name: string
  description: string
  inputSchema: Record<string, unknown>
}

export interface SkillPackageIssue {
  level: 'ERROR' | 'WARNING'
  code: string
  message: string
}

export interface SkillPackageValidation {
  status: 'VALID' | 'INVALID' | 'INCOMPLETE'
  issues: SkillPackageIssue[]
}

export interface SkillPackageImport {
  capabilityId: string | null
  name: string | null
  version: number | null
  packageSha256: string | null
  packageSize: number | null
  manifest: Record<string, unknown>
  skillMarkdown: string
  validation: SkillPackageValidation
}

export interface SkillPackageVersion {
  id: string
  capabilityId: string
  version: number
  packageSha256: string
  packageSize: number
  source: string
  validationStatus: 'VALID' | 'INVALID' | 'INCOMPLETE'
  validationIssues: SkillPackageIssue[]
  published: boolean
  createdAt: string | null
  publishedAt: string | null
}

export interface McpOperation {
  success: boolean
  errorClass: string | null
  tools: McpToolSnapshot[]
}

export interface CapabilityListParams {
  type?: CapabilityType
  status?: CapabilityStatus
  keyword?: string
  page: number
  limit: number
}

interface RequestOptions { signal?: AbortSignal }
interface DeviceRequestOptions extends RequestOptions { admin?: boolean }

const base = '/admin/companion/capabilities'

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

function isInteger(value: unknown, minimum = Number.MIN_SAFE_INTEGER): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum
}

function nonNegativeInteger(value: unknown): number | null {
  if (isInteger(value, 0)) return value
  if (typeof value !== 'string' || !/^(0|[1-9]\d*)$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}

function optionalString(value: unknown): value is string | null | undefined {
  return value === null || value === undefined || typeof value === 'string'
}

function stringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function enumValue<T extends string>(value: unknown, allowed: readonly T[]): value is T {
  return typeof value === 'string' && allowed.includes(value as T)
}

function sensitiveKey(key: string) {
  const normalized = key.replace(/([a-z0-9])([A-Z])/g, '$1_$2').replace(/\W+/g, '_').toLowerCase()
  return /(^|_)(token|secret|password|authorization|credential|headers?|env|config|runtime)($|_)/.test(normalized)
    || normalized.includes('api_key') || normalized.includes('private_key')
}

function redact(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redact)
  if (!isRecord(value)) return value
  const safe: Record<string, unknown> = {}
  for (const [key, item] of Object.entries(value)) {
    if (!sensitiveKey(key)) safe[key] = redact(item)
  }
  return safe
}

function protocolError(message: string, data: unknown, response: AxiosResponse) {
  return new ApiProtocolError(message, redact(data), response.config)
}

function unwrap(response: AxiosResponse<ApiResult<unknown>>) {
  const result: unknown = response.data
  if (!isRecord(result) || typeof result.code !== 'number' || typeof result.msg !== 'string' || !('data' in result)) {
    throw protocolError('响应数据格式错误', result, response)
  }
  if (result.code !== 0) {
    throw new ApiError(result.code, result.msg || '请求失败', redact(result.data), response.config)
  }
  return result.data
}

function requestConfig(options?: RequestOptions) {
  return options?.signal ? { signal: options.signal } : undefined
}

function multipartConfig(options?: RequestOptions) {
  return { ...(requestConfig(options) ?? {}), headers: { 'Content-Type': 'multipart/form-data' } }
}

function record(value: unknown, response: AxiosResponse, message: string) {
  if (!isRecord(value)) throw protocolError(message, value, response)
  return value
}

function parseTrigger(value: unknown, response: AxiosResponse): SkillTrigger {
  const item = record(value, response, 'Skill 触发规则格式错误')
  if (!enumValue(item.type, ['KEYWORD', 'REGEX', 'POSITIVE_EXAMPLE', 'NEGATIVE_EXAMPLE'] as const)
    || typeof item.value !== 'string' || !isInteger(item.priority)
    || typeof item.caseSensitive !== 'boolean' || typeof item.enabled !== 'boolean') {
    throw protocolError('Skill 触发规则字段错误', item, response)
  }
  return { type: item.type, value: item.value, priority: item.priority,
    caseSensitive: item.caseSensitive, enabled: item.enabled }
}

function parseTool(value: unknown, response: AxiosResponse): SkillTool {
  const item = record(value, response, 'Skill 工具格式错误')
  if (!enumValue(item.toolType, ['PLUGIN', 'MCP', 'DEVICE_TOOL'] as const)
    || typeof item.toolRefId !== 'string' || !item.toolRefId || typeof item.toolName !== 'string' || !item.toolName
    || !optionalString(item.alias) || !optionalString(item.purpose)
    || !isRecord(item.defaultParams) || typeof item.required !== 'boolean' || !isInteger(item.sortOrder)) {
    throw protocolError('Skill 工具字段错误', item, response)
  }
  return { toolType: item.toolType, toolRefId: item.toolRefId, toolName: item.toolName,
    alias: item.alias ?? null, purpose: item.purpose ?? null, defaultParams: item.defaultParams,
    required: item.required, sortOrder: item.sortOrder }
}

function parsePlugin(value: unknown, response: AxiosResponse): PluginDefinition | null {
  if (value === null || value === undefined) return null
  const item = record(value, response, 'Plugin 定义格式错误')
  if (typeof item.executorName !== 'string' || !item.executorName || !isRecord(item.inputSchema)
    || !isRecord(item.configSchema) || !stringArray(item.secretFields) || !isRecord(item.defaultConfig)) {
    throw protocolError('Plugin 定义字段错误', item, response)
  }
  return { executorName: item.executorName, inputSchema: item.inputSchema,
    configSchema: item.configSchema, secretFields: item.secretFields, defaultConfig: item.defaultConfig }
}

function parseMcp(value: unknown, response: AxiosResponse): McpDefinition | null {
  if (value === null || value === undefined) return null
  const item = record(value, response, 'MCP 定义格式错误')
  if (!enumValue(item.transport, ['STDIO', 'SSE', 'STREAMABLE_HTTP'] as const)
    || !isRecord(item.connectionConfig) || !isRecord(item.secretRefs)
    || !Object.values(item.secretRefs).every((entry) => typeof entry === 'string')
    || (item.approvedCommandTemplate !== null && item.approvedCommandTemplate !== undefined
      && !isRecord(item.approvedCommandTemplate))
    || (item.healthStatus !== null && item.healthStatus !== undefined
      && !enumValue(item.healthStatus, ['UNKNOWN', 'HEALTHY', 'UNHEALTHY'] as const))
    || !optionalString(item.lastError) || !optionalString(item.lastCheckedAt)) {
    throw protocolError('MCP 定义字段错误', item, response)
  }
  return { transport: item.transport, connectionConfig: item.connectionConfig,
    secretRefs: item.secretRefs as Record<string, string>,
    approvedCommandTemplate: item.approvedCommandTemplate as Record<string, unknown> | null ?? null,
    healthStatus: enumValue(item.healthStatus, ['UNKNOWN', 'HEALTHY', 'UNHEALTHY'] as const) ? item.healthStatus : null,
    lastError: optionalString(item.lastError) ? item.lastError ?? null : null,
    lastCheckedAt: optionalString(item.lastCheckedAt) ? item.lastCheckedAt ?? null : null }
}

function parseCapability(value: unknown, response: AxiosResponse): Capability {
  const item = record(value, response, '能力数据格式错误')
  if (typeof item.id !== 'string' || !item.id || !enumValue(item.type, ['SKILL', 'PLUGIN', 'MCP_SERVER'] as const)
    || typeof item.name !== 'string' || !item.name || !optionalString(item.description)
    || !enumValue(item.status, ['DRAFT', 'PUBLISHED', 'DISABLED'] as const)
    || !isInteger(item.draftVersion, 1)
    || (item.publishedVersion !== null && item.publishedVersion !== undefined && !isInteger(item.publishedVersion, 1))
    || !optionalString(item.executionPrompt)
    || (item.semanticThreshold !== null && item.semanticThreshold !== undefined
      && (typeof item.semanticThreshold !== 'number' || item.semanticThreshold < 0 || item.semanticThreshold > 1))
    || (item.responseMode !== null && item.responseMode !== undefined
      && !enumValue(item.responseMode, ['LLM', 'FIXED'] as const))
    || (item.timeoutMs !== null && item.timeoutMs !== undefined && !isInteger(item.timeoutMs, 0))
    || !optionalString(item.failureMessage) || !Array.isArray(item.triggers) || !Array.isArray(item.tools)
    || (item.packageVersion !== null && item.packageVersion !== undefined && !isInteger(item.packageVersion, 1))
    || (item.packageSha256 !== null && item.packageSha256 !== undefined
      && (typeof item.packageSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(item.packageSha256)))
    || !optionalString(item.packageSource) || !optionalString(item.packageValidationStatus)
    || !optionalString(item.createdAt) || !optionalString(item.updatedAt)) {
    throw protocolError('能力数据字段错误', item, response)
  }
  const plugin = parsePlugin(item.plugin, response)
  const mcp = parseMcp(item.mcp, response)
  if ((item.type === 'PLUGIN' && plugin === null) || (item.type === 'MCP_SERVER' && mcp === null)) {
    throw protocolError('能力类型定义缺失', item, response)
  }
  const result: Capability = { id: item.id, type: item.type, name: item.name, description: item.description ?? null,
    status: item.status, draftVersion: item.draftVersion, publishedVersion: item.publishedVersion ?? null,
    executionPrompt: item.executionPrompt ?? null, semanticThreshold: item.semanticThreshold ?? null,
    responseMode: item.responseMode ?? null, timeoutMs: item.timeoutMs ?? null,
    failureMessage: item.failureMessage ?? null, triggers: item.triggers.map((entry) => parseTrigger(entry, response)),
    tools: item.tools.map((entry) => parseTool(entry, response)), plugin, mcp,
    createdAt: item.createdAt ?? null, updatedAt: item.updatedAt ?? null }
  if ('packageVersion' in item) result.packageVersion = item.packageVersion as number | null ?? null
  if ('packageSha256' in item) result.packageSha256 = item.packageSha256 as string | null ?? null
  if ('packageSource' in item) result.packageSource = item.packageSource as string | null ?? null
  if ('packageValidationStatus' in item) result.packageValidationStatus = item.packageValidationStatus as string | null ?? null
  return result
}

function parsePage(value: unknown, response: AxiosResponse): CapabilityPage {
  const item = record(value, response, '能力分页格式错误')
  if (!isInteger(item.total, 0) || !Array.isArray(item.list)) throw protocolError('能力分页字段错误', item, response)
  return { total: item.total, list: item.list.map((entry) => parseCapability(entry, response)) }
}

function parseBinding(value: unknown, response: AxiosResponse): DeviceSkillBinding {
  const item = record(value, response, '设备 Skill 绑定格式错误')
  const configVersion = nonNegativeInteger(item.configVersion)
  if (typeof item.skillId !== 'string' || !item.skillId || !optionalString(item.skillName)
    || !enumValue(item.versionMode, ['LATEST', 'FIXED'] as const)
    || (item.fixedVersion !== null && item.fixedVersion !== undefined && !isInteger(item.fixedVersion, 1))
    || !isInteger(item.resolvedVersion, 1) || typeof item.enabled !== 'boolean' || !isRecord(item.overrides)
    || !isInteger(item.triggerPriority) || configVersion === null) {
    throw protocolError('设备 Skill 绑定字段错误', item, response)
  }
  return { skillId: item.skillId, skillName: item.skillName ?? null, versionMode: item.versionMode,
    fixedVersion: item.fixedVersion ?? null, resolvedVersion: item.resolvedVersion, enabled: item.enabled,
    overrides: item.overrides, triggerPriority: item.triggerPriority, configVersion }
}

function parseDeviceSkillCatalogItem(value: unknown, response: AxiosResponse): DeviceSkillCatalogItem {
  const item = record(value, response, '设备 Skill 目录格式错误')
  if (typeof item.skillId !== 'string' || !item.skillId || typeof item.name !== 'string' || !item.name
    || !optionalString(item.description) || !isInteger(item.publishedVersion, 1)
    || !Array.isArray(item.versions) || !item.versions.every((version) => isInteger(version, 1))
    || !stringArray(item.overridableFields) || !isRecord(item.defaults) || typeof item.available !== 'boolean'
    || !optionalString(item.unavailableReason)) {
    throw protocolError('设备 Skill 目录字段错误', item, response)
  }
  return { skillId: item.skillId, name: item.name, description: item.description ?? null,
    publishedVersion: item.publishedVersion, versions: item.versions,
    overridableFields: item.overridableFields, defaults: item.defaults, available: item.available,
    unavailableReason: item.unavailableReason ?? null }
}

function parseMcpTool(value: unknown, response: AxiosResponse): McpToolSnapshot {
  const item = record(value, response, 'MCP 工具快照格式错误')
  if (typeof item.id !== 'string' || !item.id || typeof item.mcpServerId !== 'string' || !item.mcpServerId
    || typeof item.toolName !== 'string' || !item.toolName || typeof item.inputSchemaJson !== 'string'
    || typeof item.schemaSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(item.schemaSha256)
    || !enumValue(item.status, ['DISCOVERED', 'ACTIVE', 'DRIFTED', 'MISSING'] as const)
    || (item.approved !== 0 && item.approved !== 1) || !optionalString(item.syncedAt)
    || !optionalString(item.createdAt) || !optionalString(item.updatedAt)) {
    throw protocolError('MCP 工具快照字段错误', item, response)
  }
  return { id: item.id, mcpServerId: item.mcpServerId, toolName: item.toolName,
    inputSchemaJson: item.inputSchemaJson, schemaSha256: item.schemaSha256, status: item.status,
    approved: item.approved, syncedAt: item.syncedAt ?? null, createdAt: item.createdAt ?? null,
    updatedAt: item.updatedAt ?? null }
}

function parsePluginExecutor(value: unknown, response: AxiosResponse): PluginExecutor {
  const item = record(value, response, 'Plugin 执行器格式错误')
  if (typeof item.name !== 'string' || !item.name || typeof item.description !== 'string'
    || !isRecord(item.inputSchema)) {
    throw protocolError('Plugin 执行器字段错误', item, response)
  }
  return { name: item.name, description: item.description, inputSchema: item.inputSchema }
}

function parseSkillPackageIssue(value: unknown, response: AxiosResponse): SkillPackageIssue {
  const item = record(value, response, 'Skill 包校验问题格式错误')
  if (!enumValue(item.level, ['ERROR', 'WARNING'] as const)
    || typeof item.code !== 'string' || !item.code || typeof item.message !== 'string') {
    throw protocolError('Skill 包校验问题字段错误', item, response)
  }
  return { level: item.level, code: item.code, message: item.message }
}

function parseSkillPackageValidation(value: unknown, response: AxiosResponse): SkillPackageValidation {
  const item = record(value, response, 'Skill 包校验报告格式错误')
  if (!enumValue(item.status, ['VALID', 'INVALID', 'INCOMPLETE'] as const) || !Array.isArray(item.issues)) {
    throw protocolError('Skill 包校验报告字段错误', item, response)
  }
  return { status: item.status, issues: item.issues.map((issue) => parseSkillPackageIssue(issue, response)) }
}

function parseSkillPackageImport(value: unknown, response: AxiosResponse): SkillPackageImport {
  const item = record(value, response, 'Skill 包导入响应格式错误')
  const version = item.version === null || item.version === undefined ? null : item.version
  const packageSize = item.packageSize === null || item.packageSize === undefined ? null : item.packageSize
  if (!optionalString(item.capabilityId) || !optionalString(item.name)
    || (version !== null && !isInteger(version, 1))
    || (packageSize !== null && !isInteger(packageSize, 0))
    || (item.packageSha256 !== null && item.packageSha256 !== undefined
      && (typeof item.packageSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(item.packageSha256)))
    || !isRecord(item.manifest) || typeof item.skillMarkdown !== 'string') {
    throw protocolError('Skill 包导入字段错误', item, response)
  }
  return {
    capabilityId: item.capabilityId ?? null, name: item.name ?? null, version,
    packageSha256: item.packageSha256 ?? null, packageSize, manifest: item.manifest,
    skillMarkdown: item.skillMarkdown, validation: parseSkillPackageValidation(item.validation, response),
  }
}

function parseSkillPackageVersion(value: unknown, response: AxiosResponse): SkillPackageVersion {
  const item = record(value, response, 'Skill 包版本格式错误')
  if (typeof item.id !== 'string' || !item.id || typeof item.capabilityId !== 'string' || !item.capabilityId
    || !isInteger(item.version, 1) || typeof item.packageSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(item.packageSha256)
    || !isInteger(item.packageSize, 0) || typeof item.source !== 'string' || !item.source
    || !enumValue(item.validationStatus, ['VALID', 'INVALID', 'INCOMPLETE'] as const)
    || !Array.isArray(item.validationIssues) || typeof item.published !== 'boolean'
    || !optionalString(item.createdAt) || !optionalString(item.publishedAt)) {
    throw protocolError('Skill 包版本字段错误', item, response)
  }
  return {
    id: item.id, capabilityId: item.capabilityId, version: item.version,
    packageSha256: item.packageSha256, packageSize: item.packageSize, source: item.source,
    validationStatus: item.validationStatus,
    validationIssues: item.validationIssues.map((issue) => parseSkillPackageIssue(issue, response)),
    published: item.published, createdAt: item.createdAt ?? null, publishedAt: item.publishedAt ?? null,
  }
}

function parseMcpOperation(value: unknown, response: AxiosResponse): McpOperation {
  const item = record(value, response, 'MCP 操作响应格式错误')
  if (typeof item.success !== 'boolean' || !optionalString(item.errorClass) || !Array.isArray(item.tools)) {
    throw protocolError('MCP 操作响应字段错误', item, response)
  }
  return { success: item.success, errorClass: item.errorClass ?? null,
    tools: item.tools.map((tool) => parseMcpTool(tool, response)) }
}

function parseRoutePreview(value: unknown, response: AxiosResponse): CapabilityRoutePreview {
  const item = record(value, response, '能力路由预览格式错误')
  if (!stringArray(item.deterministicMatches) || typeof item.semanticRequired !== 'boolean'
    || !stringArray(item.eligibleSkillIds) || !optionalString(item.selectedSkillId) || !stringArray(item.allowedTools)) {
    throw protocolError('能力路由预览字段错误', item, response)
  }
  return { deterministicMatches: item.deterministicMatches, semanticRequired: item.semanticRequired,
    eligibleSkillIds: item.eligibleSkillIds, selectedSkillId: item.selectedSkillId ?? null,
    allowedTools: item.allowedTools }
}

function parseArray<T>(value: unknown, response: AxiosResponse, parser: (item: unknown, response: AxiosResponse) => T,
  message: string): T[] {
  if (!Array.isArray(value)) throw protocolError(message, value, response)
  return value.map((item) => parser(item, response))
}

function encoded(value: string) { return encodeURIComponent(value) }

export async function listCapabilities(params: CapabilityListParams, options?: RequestOptions): Promise<CapabilityPage> {
  if (!isInteger(params.page, 1) || !isInteger(params.limit, 1)) throw new RangeError('page and limit must be positive integers')
  const response = await http.get<ApiResult<unknown>>(base, { params, ...requestConfig(options) })
  return parsePage(unwrap(response), response)
}

export async function getCapability(id: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`${base}/${encoded(id)}`, requestConfig(options))
  return parseCapability(unwrap(response), response)
}

export async function createCapability(input: CapabilitySaveInput, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(base, input, requestConfig(options))
  return parseCapability(unwrap(response), response)
}

export async function updateCapability(id: string, input: CapabilitySaveInput, options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(`${base}/${encoded(id)}`, input, requestConfig(options))
  return parseCapability(unwrap(response), response)
}

export async function publishCapability(id: string, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(`${base}/${encoded(id)}/publish`, undefined, requestConfig(options))
  return parseCapability(unwrap(response), response)
}

export async function setCapabilityStatus(id: string, status: CapabilityStatus, options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(`${base}/${encoded(id)}/status`, { status }, requestConfig(options))
  if (unwrap(response) !== null) throw protocolError('能力状态响应格式错误', response.data, response)
}

export async function deleteCapability(id: string, options?: RequestOptions) {
  const response = await http.delete<ApiResult<unknown>>(`${base}/${encoded(id)}`, requestConfig(options))
  if (unwrap(response) !== null) throw protocolError('删除能力响应格式错误', response.data, response)
}

export async function getCapabilitySecretStatus(id: string, options?: RequestOptions): Promise<Record<string, boolean>> {
  const response = await http.get<ApiResult<unknown>>(`${base}/${encoded(id)}/secrets`, requestConfig(options))
  const value = unwrap(response)
  if (!isRecord(value) || !Object.values(value).every((entry) => typeof entry === 'boolean')) {
    throw protocolError('能力密钥状态格式错误', value, response)
  }
  return value as Record<string, boolean>
}

export async function saveCapabilitySecret(id: string, name: string, value: string, options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(
    `${base}/${encoded(id)}/secrets/${encoded(name)}`, { value }, requestConfig(options),
  )
  const result = unwrap(response)
  if (!isRecord(result) || typeof result.configured !== 'boolean') throw protocolError('能力密钥响应格式错误', result, response)
  return { configured: result.configured }
}

export async function previewCapabilityRoute(deviceId: string, utterance: string, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(
    `${base}/route-preview`, { deviceId, utterance }, requestConfig(options),
  )
  return parseRoutePreview(unwrap(response), response)
}

export async function listMcpTools(capabilityId: string, options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`${base}/${encoded(capabilityId)}/mcp/tools`, requestConfig(options))
  return parseArray(unwrap(response), response, parseMcpTool, 'MCP 工具快照列表格式错误')
}

export async function approveMcpTools(capabilityId: string, approvedToolIds: string[], options?: RequestOptions) {
  const response = await http.put<ApiResult<unknown>>(
    `${base}/${encoded(capabilityId)}/mcp/tools`, { approvedToolIds }, requestConfig(options),
  )
  return parseArray(unwrap(response), response, parseMcpTool, 'MCP 工具快照列表格式错误')
}

export async function listPluginExecutors(options?: RequestOptions) {
  const response = await http.get<ApiResult<unknown>>(`${base}/plugin-executors`, requestConfig(options))
  return parseArray(unwrap(response), response, parsePluginExecutor, 'Plugin 执行器列表格式错误')
}

export async function testMcpConnection(capabilityId: string, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(
    `${base}/${encoded(capabilityId)}/mcp/test`, undefined, requestConfig(options),
  )
  return parseMcpOperation(unwrap(response), response)
}

export async function syncMcpTools(capabilityId: string, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(
    `${base}/${encoded(capabilityId)}/mcp/sync`, undefined, requestConfig(options),
  )
  return parseMcpOperation(unwrap(response), response)
}

export async function importSkillPackage(file: File, options?: RequestOptions): Promise<SkillPackageImport> {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiResult<unknown>>(`${base}/skill-packages/import`, form, multipartConfig(options))
  return parseSkillPackageImport(unwrap(response), response)
}

export async function uploadSkillPackage(id: string, file: File, options?: RequestOptions): Promise<Capability> {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiResult<unknown>>(`${base}/${encoded(id)}/packages`, form, multipartConfig(options))
  return parseCapability(unwrap(response), response)
}

export async function createSkillFromPackage(file: File, options?: RequestOptions): Promise<Capability> {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiResult<unknown>>(`${base}/skill-packages`, form, multipartConfig(options))
  return parseCapability(unwrap(response), response)
}

export async function downloadSkillPackage(id: string, version: number, options?: RequestOptions): Promise<Blob> {
  if (!isInteger(version, 1)) throw new RangeError('version must be a positive integer')
  const response = await http.get<Blob>(`${base}/${encoded(id)}/packages/${version}/download`, {
    responseType: 'blob', ...requestConfig(options),
  })
  return response.data
}

export async function getSkillPackageDraftValidation(id: string, options?: RequestOptions): Promise<SkillPackageValidation> {
  const response = await http.get<ApiResult<unknown>>(
    `${base}/${encoded(id)}/packages/draft/validation`, requestConfig(options),
  )
  return parseSkillPackageValidation(unwrap(response), response)
}

export async function listSkillPackages(id: string, options?: RequestOptions): Promise<SkillPackageVersion[]> {
  const response = await http.get<ApiResult<unknown>>(`${base}/${encoded(id)}/packages`, requestConfig(options))
  return parseArray(unwrap(response), response, parseSkillPackageVersion, 'Skill 包版本列表格式错误')
}

export async function importLocalMcpConfig(document: Record<string, unknown>, options?: RequestOptions) {
  const response = await http.post<ApiResult<unknown>>(`${base}/mcp/import-local`, document, requestConfig(options))
  const result = unwrap(response)
  if (!isRecord(result) || !stringArray(result.imported) || !stringArray(result.skipped)) {
    throw protocolError('MCP 本地配置导入响应格式错误', result, response)
  }
  return { imported: result.imported, skipped: result.skipped } satisfies McpLocalConfigImportResult
}

function deviceSkillPath(deviceId: string, admin = false) {
  return admin ? `${base}/devices/${encoded(deviceId)}/skills` : `/companion/devices/${encoded(deviceId)}/skills`
}

export async function listDeviceSkills(deviceId: string, options?: DeviceRequestOptions) {
  const response = await http.get<ApiResult<unknown>>(deviceSkillPath(deviceId, options?.admin), requestConfig(options))
  return parseArray(unwrap(response), response, parseBinding, '设备 Skill 绑定列表格式错误')
}

export async function listDeviceSkillCatalog(deviceId: string, options?: DeviceRequestOptions) {
  const response = await http.get<ApiResult<unknown>>(
    `${deviceSkillPath(deviceId, options?.admin)}/catalog`, requestConfig(options),
  )
  return parseArray(unwrap(response), response, parseDeviceSkillCatalogItem, '设备 Skill 目录列表格式错误')
}

export async function saveDeviceSkills(deviceId: string, bindings: DeviceSkillBindingInput[], options?: DeviceRequestOptions) {
  const response = await http.put<ApiResult<unknown>>(
    deviceSkillPath(deviceId, options?.admin), bindings, requestConfig(options),
  )
  return parseArray(unwrap(response), response, parseBinding, '设备 Skill 绑定列表格式错误')
}
