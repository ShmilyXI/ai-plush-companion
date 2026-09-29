import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

test.describe('console quality gate', () => {
  test('login form has semantic fields, visible focus, and no narrow overflow', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: '登录' })).toBeVisible()
    const username = page.getByPlaceholder('用户名或手机号')
    const password = page.getByPlaceholder('请输入密码')
    await expect(username).toHaveAttribute('autocomplete', 'username')
    await expect(password).toHaveAttribute('autocomplete', 'current-password')
    await username.focus()
    await expect(username).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(password).toBeFocused()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy()
  })

  test('loading and validation remain observable through keyboard submission', async ({ page }) => {
    await page.goto('/login')
    await page.getByPlaceholder('用户名或手机号').fill('')
    await page.getByPlaceholder('请输入密码').fill('')
    await page.getByRole('button', { name: '进入管理台' }).press('Enter')
    await expect(page.getByText('请输入账号')).toBeVisible()
    await expect(page.getByText('请输入密码')).toBeVisible()
  })

  test('login surface has no critical accessibility violations and produces a visual artifact', async ({ page }, testInfo) => {
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: '登录' })).toBeVisible()
    const results = await new AxeBuilder({ page }).analyze()
    expect(results.violations.filter((item) => ['critical', 'serious'].includes(item.impact || '')).map((item) => item.id)).toEqual([])
    await page.screenshot({ path: testInfo.outputPath('login.png'), fullPage: true })
  })

  test('agent editor exposes aggregate capabilities and publish workflow', async ({ page }, testInfo) => {
    let published = false
    await page.route('**/zixuan/**', async (route) => {
      const url = new URL(route.request().url())
      const path = url.pathname.replace(/^.*\/zixuan/, '')
      const json = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
      if (path === '/user/info') return json({ id: 7, username: 'demo', superAdmin: 0, status: 1 })
      if (path === '/companion/profiles/profile-a' && route.request().method() === 'GET') return json({
        id: 'profile-a', name: '小满', relationMode: 'friend', userAddress: '小夏', personality: '温柔', systemPrompt: '提示词',
        screenExpressionEnabled: 1, cameraPreferenceEnabled: 0, templateId: null,
        llmModelId: 'llm-a', llmModelName: '陪伴模型', ttsModelId: 'tts-a', ttsModelName: '语音', ttsVoiceId: 'voice-a',
        ttsVoiceName: '晴岚', ttsLanguage: 'zh-CN', createdAt: '2026-01-01', updatedAt: '2026-01-02', activeVersionNo: 2,
        models: [{ modelType: 'TTS', source: 'global', resourceId: 'tts-a', enabled: true, unavailableReason: null }], effectiveModels: [],
        boundDevices: [{ id: 'device-a', alias: '客厅设备', macAddress: 'AA:BB', board: 'cam-s3', appVersion: '1.2.3', online: true }],
        memoryPolicy: { scope: 'device', namespace: 'user-agent-device', summaryMemorySource: 'device-namespace' },
        skills: [{ skillId: 'skill-weather', versionMode: 'FIXED', fixedVersion: 3, overrideJson: '{}', triggerPriority: 10, enabled: true }],
      })
      if (path === '/companion/profiles/profile-a/model-options') return json([{ id: 'tts-a', modelType: 'TTS', name: '语音', source: 'global', providerCode: 'edge', enabled: true, isDefault: false, vendorName: null, protocol: null, credentialStatus: 'configured', unavailableReason: null }])
      if (path === '/models/tts-a/voices') return json([{ id: 'voice-a', name: '晴岚', voiceDemo: null, languages: 'zh-CN', isClone: false }])
      if (path === '/agent/profile-a/snapshots' && route.request().method() === 'GET') return json({ total: 1, list: [{ id: 'snapshot-2', versionNo: 2, source: 'publish', createdAt: '2026-01-02' }] })
      if (path === '/agent/profile-a/snapshots/publish' && route.request().method() === 'POST') { published = true; return json(null) }
      return json(path.includes('/snapshots') ? { total: 0, list: [] } : [])
    })
    await page.addInitScript(() => localStorage.setItem('companion-console.session', JSON.stringify({ token: 'browser-token' })))
    await page.goto('/profiles/profile-a')
    await expect(page.getByRole('heading', { name: '编辑陪伴角色' })).toBeVisible()
    if (testInfo.project.name === 'narrow') {
      await page.getByRole('button', { name: 'ellipsis' }).click()
      await page.getByRole('option', { name: '设备能力' }).click()
      await page.reload()
    } else {
      await page.getByRole('tab', { name: '设备能力' }).click()
    }
    await expect(page.getByRole('tab', { name: '设备能力' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('tab', { name: '设备能力' })).toBeVisible()
    await expect(page.locator('h4').filter({ hasText: '客厅设备' }).last()).toBeVisible()
    await expect(page.getByLabel('Skill 编号')).toHaveValue('skill-weather')
    await expect(page).toHaveScreenshot('agent-editor-capabilities.png', { fullPage: true })
    if (testInfo.project.name === 'narrow') {
      await page.getByRole('button', { name: 'ellipsis' }).click()
      await page.getByRole('option', { name: '版本记录' }).click()
      await page.reload()
    } else {
      await page.getByRole('tab', { name: '版本记录' }).click()
    }
    await expect(page.getByRole('tab', { name: '版本记录' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('tab', { name: '版本记录' })).toBeVisible()
    await expect(page.getByRole('button', { name: '发布当前草稿' })).toBeVisible()
    await page.getByRole('button', { name: '发布当前草稿' }).click()
    await expect.poll(() => published).toBeTruthy()
    await expect(page).toHaveScreenshot('agent-editor-publish.png', { fullPage: true })
  })

  test('agent version controls expose activation and rollback on narrow and desktop layouts', async ({ page }) => {
    let activated = false
    await page.route('**/zixuan/**', async (route) => {
      const url = new URL(route.request().url())
      const path = url.pathname.replace(/^.*\/zixuan/, '')
      const json = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
      if (path === '/user/info') return json({ id: 7, username: 'demo', superAdmin: 0, status: 1 })
      if (path === '/companion/profiles/profile-a' && route.request().method() === 'GET') return json({
        id: 'profile-a', name: '小满', relationMode: 'friend', userAddress: '小夏', personality: '温柔', systemPrompt: '提示词', screenExpressionEnabled: 1, cameraPreferenceEnabled: 0, templateId: null,
        llmModelId: null, llmModelName: null, ttsModelId: null, ttsModelName: null, ttsVoiceId: null, ttsVoiceName: null, ttsLanguage: null, createdAt: '2026-01-01', updatedAt: '2026-01-02', activeVersionNo: 2,
        models: [], effectiveModels: [], boundDevices: [], memoryPolicy: {}, skills: [],
      })
      if (path === '/companion/profiles/profile-a/model-options') return json([])
      if (path === '/agent/profile-a/snapshots' && route.request().method() === 'GET') return json({ total: 2, list: [
        { id: 'snapshot-2', versionNo: 2, source: 'publish', createdAt: '2026-01-02' },
        { id: 'snapshot-1', versionNo: 1, source: 'initial', createdAt: '2026-01-01' },
      ] })
      if (path === '/agent/profile-a/snapshots/snapshot-1' && route.request().method() === 'GET') return json({ id: 'snapshot-1', versionNo: 1, source: 'initial', createdAt: '2026-01-01', snapshotData: {
        agentName: '旧小满', relationMode: 'friend', userAddress: '小夏', personality: '温柔', systemPrompt: '旧提示词', screenExpressionEnabled: 1, cameraPreferenceEnabled: 0,
        llmModelId: null, asrModelId: null, ttsModelId: null, vadModelId: null, vllmModelId: null, memModelId: null, ttsVoiceId: null,
      } })
      if (path === '/agent/profile-a/snapshots/snapshot-1/activate' && route.request().method() === 'POST') { activated = true; return json(null) }
      return json([])
    })
    await page.addInitScript(() => localStorage.setItem('companion-console.session', JSON.stringify({ token: 'browser-token' })))
    await page.goto('/profiles/profile-a?tab=versions')
    await expect(page.getByRole('heading', { name: '编辑陪伴角色' })).toBeVisible()
    await expect(page.getByRole('button', { name: '激活版本 1' })).toBeVisible()
    await expect(page.getByRole('button', { name: '恢复版本 1' })).toBeVisible()
    await page.getByRole('button', { name: '激活版本 1' }).click()
    await expect.poll(() => activated).toBeTruthy()
    await expect(page.getByText('版本 1 已激活')).toBeVisible()
    await expect(page).toHaveScreenshot(`agent-editor-version-controls-${await page.evaluate(() => window.innerWidth)}.png`, { fullPage: true })
    await page.getByRole('button', { name: '恢复版本 1' }).click()
    await expect(page.getByRole('dialog', { name: '恢复版本 1' })).toBeVisible()
    await page.getByRole('button', { name: '确认恢复' }).click()
    await expect(page.getByLabel('角色名称')).toHaveValue('旧小满')
  })

  test('memory migration flow keeps counts, retry state, and narrow layout usable', async ({ page }) => {
    let migrationStarted = false
    await page.route('**/zixuan/**', async (route) => {
      const url = new URL(route.request().url())
      const path = url.pathname.replace(/^.*\/zixuan/, '')
      const json = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
      if (path === '/user/info') return json({ id: 7, username: 'demo', superAdmin: 0, status: 1 })
      if (path === '/companion/devices' && route.request().method() === 'GET') return json([
        { id: 'device-a', macAddress: 'AA:BB', alias: '床头伙伴', online: true, appVersion: '1.2.3', hasDisplay: true, hasCamera: false, activeProfileId: 'profile-a', debugLogEnabled: false, board: 'compact', lastConnectedAt: '2026-08-20' },
        { id: 'device-b', macAddress: 'CC:DD', alias: '客厅伙伴', online: false, appVersion: '1.2.2', hasDisplay: true, hasCamera: true, activeProfileId: 'profile-a', debugLogEnabled: false, board: 'cam-s3', lastConnectedAt: '2026-08-19' },
      ])
      if (path === '/companion/devices/device-a/memories' || path === '/companion/devices/device-b/memories') return json([])
      if (path === '/companion/memory-migrations' && route.request().method() === 'GET') return json([{ id: 'migration-failed', agentId: 'profile-a', sourceDeviceId: 'device-a', targetDeviceId: 'device-b', mode: 'overwrite', sourceCount: 2, targetCount: 1, importedCount: 0, skippedCount: 0, outcome: 'FAILED', retryable: true, recovered: true, operatorId: 7, createdAt: '2026-08-20' }])
      if (path === '/companion/memory-migrations/preview') return json({ agentId: 'profile-a', sourceDeviceId: 'device-a', targetDeviceId: 'device-b', sourceCount: 2, targetCount: 1, mode: 'merge' })
      if (path === '/companion/memory-migrations/migration-failed/retry' && route.request().method() === 'POST') return json({ id: 'migration-retry', agentId: 'profile-a', sourceDeviceId: 'device-a', targetDeviceId: 'device-b', mode: 'overwrite', sourceCount: 2, targetCount: 1, importedCount: 2, skippedCount: 0, outcome: 'SUCCEEDED', retryable: false, recovered: true, operatorId: 7, createdAt: '2026-08-20' })
      if (path === '/companion/memory-migrations' && route.request().method() === 'POST') { migrationStarted = true; return json({ id: 'migration-2', agentId: 'profile-a', sourceDeviceId: 'device-a', targetDeviceId: 'device-b', mode: 'merge', sourceCount: 2, targetCount: 1, importedCount: 1, skippedCount: 1, outcome: 'SUCCEEDED', retryable: false, recovered: true, operatorId: 7, createdAt: '2026-08-20' }) }
      return json([])
    })
    await page.addInitScript(() => localStorage.setItem('companion-console.session', JSON.stringify({ token: 'browser-token' })))
    await page.goto('/memories')
    await expect(page.getByRole('heading', { name: '记忆' })).toBeVisible()
    const results = await new AxeBuilder({ page }).analyze()
    expect(results.violations.filter((item) => ['critical', 'serious'].includes(item.impact || '')).map((item) => ({
      id: item.id,
      nodes: item.nodes.map((node) => ({ target: node.target, summary: node.failureSummary })),
    }))).toEqual([])
    await page.getByRole('button', { name: '迁移整库记忆' }).click()
    await page.getByRole('button', { name: '重试' }).click()
    await expect(page.getByText('迁移已重试')).toBeVisible()
    await page.getByRole('button', { name: '读取迁移预览' }).click()
    await expect(page.getByText('来源 2 条，目标 1 条，模式为合并')).toBeVisible()
    await page.getByRole('button', { name: '开始迁移' }).click()
    await expect.poll(() => migrationStarted).toBeTruthy()
    await expect(page.getByText('迁移完成，导入 1 条，跳过 1 条')).toBeVisible()
    await expect(page).toHaveScreenshot('memory-migration.png', { fullPage: true })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy()
  })

  test('device effective capability projection is read-only, accessible, and responsive', async ({ page }, testInfo) => {
    await page.route('**/zixuan/**', async (route) => {
      const url = new URL(route.request().url())
      const path = url.pathname.replace(/^.*\/zixuan/, '')
      const json = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
      if (path === '/user/info') return json({ id: 7, username: 'demo', superAdmin: 0, status: 1 })
      if (path === '/companion/devices/device-a' && route.request().method() === 'GET') return json({ id: 'device-a', macAddress: 'AA:BB', alias: '床头伙伴', online: true, appVersion: '1.2.3', hasDisplay: true, hasCamera: false, activeProfileId: 'profile-a', debugLogEnabled: false, board: 'compact', lastConnectedAt: '2026-08-20', effectiveModels: [] })
      if (path === '/companion/devices/device-a/profiles') return json([{ id: 'profile-a', name: '小满' }])
      if (path === '/companion/devices/device-a/skills/catalog') return json([
        { skillId: 'skill-weather', name: '天气查询', description: '查询天气', publishedVersion: 3, packageVersion: 3, packageSha256: 'a'.repeat(64), packageSource: 'MIGRATION', versions: [3], overridableFields: [], defaults: {}, available: true, unavailableReason: null },
        { skillId: 'skill-camera', name: '拍照', description: '需要摄像头', publishedVersion: 2, packageVersion: 2, packageSha256: 'b'.repeat(64), packageSource: 'MIGRATION', versions: [2], overridableFields: [], defaults: {}, available: false, unavailableReason: '当前设备没有摄像头' },
      ])
      if (path === '/companion/devices/device-a/skills') return json([{ skillId: 'skill-weather', skillName: '天气查询', versionMode: 'LATEST', fixedVersion: null, resolvedVersion: 3, triggerPriority: 10, enabled: true, overrides: {}, configVersion: 3 }])
      if (path === '/companion/devices/device-a/debug-logs') return json({ events: [], lastCursor: '0-0' })
      if (path === '/companion/devices/device-a/wake-word') return json({ desiredWord: null, desiredVersion: 0, activeWord: null, activeVersion: 0, status: 'IDLE', lastErrorCode: null, lastErrorMessage: null, supported: false, unsupportedReason: '设备不支持', updatedAt: null })
      return json([])
    })
    await page.addInitScript(() => localStorage.setItem('companion-console.session', JSON.stringify({ token: 'browser-token' })))
    await page.goto('/devices/device-a')
    await expect(page.getByRole('heading', { name: '床头伙伴' })).toBeVisible()
    await expect(page.getByText('有效设备能力')).toBeVisible()
    await expect(page.getByText('天气查询')).toBeVisible()
    await expect(page.getByText('当前设备没有摄像头')).toBeVisible()
    await expect(page.getByText('Skill 由智能体的已激活版本统一管理。此处只展示当前设备硬件过滤后的有效结果。')).toBeVisible()
    expect(await page.getByRole('button', { name: /绑定|保存 Skill|编辑 Skill/ }).count()).toBe(0)
    await page.getByRole('slider', { name: '音量' }).focus()
    await expect(page.getByRole('slider', { name: '音量' })).toBeFocused()
    const results = await new AxeBuilder({ page }).include('.device-skill-card').analyze()
    expect(results.violations.filter((item) => ['critical', 'serious'].includes(item.impact || '')).map((item) => item.id)).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy()
    await expect(page).toHaveScreenshot(`device-effective-capability-${testInfo.project.name}.png`, { fullPage: true })
  })

  test('agent model editor configures a missing credential without rendering stored secrets', async ({ page }) => {
    let savedBody: Record<string, unknown> | null = null
    await page.route('**/zixuan/**', async (route) => {
      const url = new URL(route.request().url())
      const path = url.pathname.replace(/^.*\/zixuan/, '')
      const json = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
      if (path === '/user/info') return json({ id: 7, username: 'demo', superAdmin: 0, status: 1 })
      if (path === '/companion/profiles/profile-a' && route.request().method() === 'GET') return json({ id: 'profile-a', name: '小满', relationMode: 'friend', userAddress: '小夏', personality: '温柔', systemPrompt: '提示词', screenExpressionEnabled: 1, cameraPreferenceEnabled: 0, templateId: null, llmModelId: 'llm-missing', llmModelName: 'DeepSeek', ttsModelId: null, ttsModelName: null, ttsVoiceId: null, ttsVoiceName: null, ttsLanguage: null, createdAt: '2026-01-01', updatedAt: '2026-01-02', activeVersionNo: 1, models: [{ modelType: 'LLM', source: 'global', resourceId: 'llm-missing', name: 'DeepSeek', enabled: false, unavailableReason: '请先配置凭据' }], effectiveModels: [], boundDevices: [], memoryPolicy: {}, skills: [] })
      if (path === '/companion/profiles/profile-a/model-options') return json([{ id: 'llm-missing', modelType: 'LLM', name: 'DeepSeek', source: 'global', providerCode: 'openai', enabled: true, isDefault: false, vendorName: 'DeepSeek', protocol: 'OpenAI 兼容', credentialStatus: 'missing', unavailableReason: '请先配置凭据' }])
      if (path === '/companion/models/catalog') return json([{ id: 'llm-missing', reference: 'global:llm-missing', modelType: 'LLM', name: 'DeepSeek', providerCode: 'openai', vendorCode: 'deepseek', vendorName: 'DeepSeek', protocol: 'OpenAI 兼容', providerTemplateId: 'openai', apiUrl: null, modelId: null, credentialRequirement: 'required', credentialConfigured: false, credentialStatus: 'missing', keyUrl: null, docsUrl: null, setupGuide: [], credentialFields: [{ key: 'api_key', label: 'API 密钥', type: 'string', required: true, secret: true, options: [], defaultValue: null }], unavailableReason: null, source: 'global', enabled: true, defaultModel: false, usageCount: 0, actions: ['configure'] }])
      if (path === '/companion/models/global/llm-missing/config' && route.request().method() === 'GET') return json({ globalModelId: 'llm-missing', apiUrl: null, modelId: null, configuredSecretKeys: [], credentialRequirement: 'required', credentialConfigured: false, credentialStatus: 'missing' })
      if (path === '/companion/models/global/llm-missing/config' && route.request().method() === 'PUT') { savedBody = route.request().postDataJSON(); return json({ globalModelId: 'llm-missing', apiUrl: null, modelId: null, configuredSecretKeys: ['api_key'], credentialRequirement: 'required', credentialConfigured: true, credentialStatus: 'configured' }) }
      if (path.startsWith('/agent/profile-a/snapshots')) return json({ total: 0, list: [] })
      return json([])
    })
    await page.addInitScript(() => localStorage.setItem('companion-console.session', JSON.stringify({ token: 'browser-token' })))
    await page.goto('/profiles/profile-a?tab=models')
    await expect(page.getByRole('heading', { name: '编辑陪伴角色' })).toBeVisible()
    await expect(page.getByText('请先配置凭据')).toBeVisible()
    await page.getByRole('button', { name: '配置凭据' }).click()
    await expect(page.getByRole('dialog', { name: '配置DeepSeek凭据' })).toBeAttached()
    await page.getByLabel('API 密钥').fill('browser-secret')
    await page.getByRole('button', { name: '保存凭据' }).click()
    await expect.poll(() => savedBody).toEqual({ secrets: { api_key: 'browser-secret' } })
    await expect(page.getByText('browser-secret')).toHaveCount(0)
  })
})
