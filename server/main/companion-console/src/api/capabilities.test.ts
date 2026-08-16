import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from './http'
import {
  approveMcpTools,
  createCapability,
  deleteCapability,
  getCapability,
  getCapabilitySecretStatus,
  importLocalMcpConfig,
  listCapabilities,
  listDeviceSkillCatalog,
  listDeviceSkills,
  listMcpTools,
  previewCapabilityRoute,
  publishCapability,
  saveCapabilitySecret,
  saveDeviceSkills,
  setCapabilityStatus,
  updateCapability,
} from './capabilities'
import type { CapabilitySaveInput } from './capabilities'

function response(data: unknown, code = 0, msg = 'success') {
  return { data: { code, msg, data }, config: {} }
}

const skill = {
  id: 'skill-weather',
  type: 'SKILL',
  name: '天气查询',
  description: '查询天气',
  status: 'PUBLISHED',
  draftVersion: 2,
  publishedVersion: 1,
  executionPrompt: '查询用户指定地点的天气。',
  semanticThreshold: 0.7,
  responseMode: 'LLM',
  timeoutMs: 15000,
  failureMessage: '天气查询失败。',
  triggers: [{ type: 'KEYWORD', value: '天气', priority: 10, caseSensitive: false, enabled: true }],
  tools: [{
    toolType: 'PLUGIN', toolRefId: 'plugin-weather', toolName: 'get_weather', alias: null,
    purpose: '天气查询', defaultParams: { location: '上海' }, required: true, sortOrder: 0,
  }],
  plugin: null,
  mcp: null,
  createdAt: '2026-08-16T00:00:00.000+00:00',
  updatedAt: '2026-08-16T01:00:00.000+00:00',
}

const mcpTool = {
  id: 'snapshot-search',
  mcpServerId: 'server-search',
  toolName: 'mcp_search',
  inputSchemaJson: '{"type":"object"}',
  schemaSha256: 'a'.repeat(64),
  status: 'ACTIVE',
  approved: 1,
  syncedAt: '2026-08-16T01:00:00.000+00:00',
  createdAt: '2026-08-16T00:00:00.000+00:00',
  updatedAt: '2026-08-16T01:00:00.000+00:00',
}

describe('capability API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('strictly parses paged capabilities with mixed triggers and mapped tools', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({ total: 1, list: [skill] }))

    await expect(listCapabilities({ type: 'SKILL', status: 'PUBLISHED', keyword: '天气', page: 2, limit: 20 }))
      .resolves.toEqual({ total: 1, list: [skill] })
    expect(http.get).toHaveBeenCalledWith('/admin/companion/capabilities', {
      params: { type: 'SKILL', status: 'PUBLISHED', keyword: '天气', page: 2, limit: 20 },
    })
  })

  it('uses encoded capability routes for CRUD, publish, status and secret writes', async () => {
    const input: CapabilitySaveInput = {
      type: 'SKILL' as const,
      name: '天气查询',
      executionPrompt: '查询天气。',
      semanticThreshold: 0.7,
      responseMode: 'LLM' as const,
      timeoutMs: 15000,
      triggers: [{ type: 'KEYWORD', value: '天气', priority: 10, caseSensitive: false, enabled: true }],
      tools: [{
        toolType: 'PLUGIN', toolRefId: 'plugin-weather', toolName: 'get_weather', alias: null,
        purpose: '天气查询', defaultParams: { location: '上海' }, required: true, sortOrder: 0,
      }],
    }
    vi.spyOn(http, 'get').mockResolvedValue(response(skill))
    vi.spyOn(http, 'post')
      .mockResolvedValueOnce(response(skill))
      .mockResolvedValueOnce(response(skill))
      .mockResolvedValueOnce(response({
        deterministicMatches: ['skill-weather'], semanticRequired: false,
        eligibleSkillIds: ['skill-weather'], selectedSkillId: 'skill-weather', allowedTools: ['get_weather'],
      }))
    vi.spyOn(http, 'put')
      .mockResolvedValueOnce(response(skill))
      .mockResolvedValueOnce(response(null))
      .mockResolvedValueOnce(response({ configured: true }))
    vi.spyOn(http, 'delete').mockResolvedValue(response(null))

    await expect(getCapability('skill/weather')).resolves.toEqual(skill)
    await expect(createCapability(input)).resolves.toEqual(skill)
    await expect(updateCapability('skill/weather', input)).resolves.toEqual(skill)
    await expect(publishCapability('skill/weather')).resolves.toEqual(skill)
    await expect(setCapabilityStatus('skill/weather', 'DISABLED')).resolves.toBeUndefined()
    await expect(saveCapabilitySecret('skill/weather', 'api/key', 'write-only-secret')).resolves.toEqual({ configured: true })
    await expect(previewCapabilityRoute('device/1', '上海天气')).resolves.toMatchObject({ selectedSkillId: 'skill-weather' })
    await expect(deleteCapability('skill/weather')).resolves.toBeUndefined()

    const encoded = 'skill%2Fweather'
    expect(http.get).toHaveBeenCalledWith(`/admin/companion/capabilities/${encoded}`, undefined)
    expect(http.post).toHaveBeenNthCalledWith(1, '/admin/companion/capabilities', input, undefined)
    expect(http.put).toHaveBeenNthCalledWith(1, `/admin/companion/capabilities/${encoded}`, input, undefined)
    expect(http.post).toHaveBeenNthCalledWith(2, `/admin/companion/capabilities/${encoded}/publish`, undefined, undefined)
    expect(http.put).toHaveBeenNthCalledWith(2, `/admin/companion/capabilities/${encoded}/status`, { status: 'DISABLED' }, undefined)
    expect(http.put).toHaveBeenNthCalledWith(3, `/admin/companion/capabilities/${encoded}/secrets/api%2Fkey`, { value: 'write-only-secret' }, undefined)
    expect(http.post).toHaveBeenNthCalledWith(3, '/admin/companion/capabilities/route-preview', {
      deviceId: 'device/1', utterance: '上海天气',
    }, undefined)
    expect(http.delete).toHaveBeenCalledWith(`/admin/companion/capabilities/${encoded}`, undefined)
  })

  it('parses secret markers, MCP snapshots and device bindings without secret values', async () => {
    const binding = {
      skillId: 'skill-weather', skillName: '天气查询', versionMode: 'LATEST', fixedVersion: null,
      resolvedVersion: 1, enabled: true, overrides: { location: '杭州' }, triggerPriority: 10, configVersion: 8,
    }
    vi.spyOn(http, 'get')
      .mockResolvedValueOnce(response({ api_key: true }))
      .mockResolvedValueOnce(response([mcpTool]))
      .mockResolvedValueOnce(response([binding]))
    vi.spyOn(http, 'put')
      .mockResolvedValueOnce(response([mcpTool]))
      .mockResolvedValueOnce(response([binding]))

    await expect(getCapabilitySecretStatus('plugin-weather')).resolves.toEqual({ api_key: true })
    await expect(listMcpTools('mcp-search')).resolves.toEqual([mcpTool])
    await expect(approveMcpTools('mcp-search', ['snapshot-search'])).resolves.toEqual([mcpTool])
    await expect(listDeviceSkills('device/1', { admin: true })).resolves.toEqual([binding])
    await expect(saveDeviceSkills('device/1', [{
      skillId: 'skill-weather', versionMode: 'LATEST', enabled: true,
      overrides: { location: '杭州' }, triggerPriority: 10,
    }], { admin: true })).resolves.toEqual([binding])

    expect(http.get).toHaveBeenNthCalledWith(1, '/admin/companion/capabilities/plugin-weather/secrets', undefined)
    expect(http.get).toHaveBeenNthCalledWith(2, '/admin/companion/capabilities/mcp-search/mcp/tools', undefined)
    expect(http.get).toHaveBeenNthCalledWith(3, '/admin/companion/capabilities/devices/device%2F1/skills', undefined)
  })

  it('loads the owner-scoped device skill catalog with versions and availability reasons', async () => {
    const catalog = [{
      skillId: 'skill-weather', name: '天气查询', description: '查询天气', publishedVersion: 2,
      versions: [1, 2], overridableFields: ['location'], defaults: { location: '上海' },
      available: true, unavailableReason: null,
    }, {
      skillId: 'skill-brightness', name: '亮度调节', description: null, publishedVersion: 1,
      versions: [1], overridableFields: ['brightness'], defaults: { brightness: 50 },
      available: false, unavailableReason: '设备未上报工具 self.screen.set_brightness',
    }]
    vi.spyOn(http, 'get').mockResolvedValue(response(catalog))

    await expect(listDeviceSkillCatalog('device/1')).resolves.toEqual(catalog)
    expect(http.get).toHaveBeenCalledWith('/companion/devices/device%2F1/skills/catalog', undefined)
  })

  it('redacts secret-like fields from protocol errors', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(response({
      total: 1,
      list: [{ ...skill, semanticThreshold: 'bad', apiKey: 'must-not-survive' }],
    }))

    const error = await listCapabilities({ page: 1, limit: 20 }).catch((caught: unknown) => caught)

    expect(error).toMatchObject({ name: 'ApiProtocolError' })
    expect(JSON.stringify((error as { data: unknown }).data)).not.toContain('must-not-survive')
  })

  it('imports a local MCP settings document without returning secret values', async () => {
    const document = { mcpServers: { search: { url: 'https://mcp.example/sse' } } }
    vi.spyOn(http, 'post').mockResolvedValue(response({ imported: ['search'], skipped: ['existing'] }))

    await expect(importLocalMcpConfig(document)).resolves.toEqual({ imported: ['search'], skipped: ['existing'] })
    expect(http.post).toHaveBeenCalledWith('/admin/companion/capabilities/mcp/import-local', document, undefined)
  })
})
