import { expect, test } from '@playwright/test'

test('playground uses the public conversation websocket on desktop and narrow layouts', async ({ page }, testInfo) => {
  await page.route('**/xiaozhi/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname.replace(/^.*\/xiaozhi/, '')
    const json = (data: unknown) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, msg: 'success', data }),
    })
    if (path === '/user/info') return json({ id: 7, username: 'demo', superAdmin: 0, status: 1 })
    if (path === '/companion/profiles') return json([{
      id: 'profile-a', name: '小满', relationMode: 'friend', userAddress: '小夏', personality: '温柔、简洁地回答',
      systemPrompt: '你是陪伴助手小满', companionCueConfig: '{}', screenExpressionEnabled: 1,
      cameraPreferenceEnabled: 0, templateId: null, llmModelId: 'llm-a', llmModelName: 'DeepSeek',
      ttsModelId: 'tts-a', ttsModelName: '火山语音', ttsVoiceId: 'voice-a', ttsVoiceName: '晴岚',
      ttsLanguage: 'zh-CN', createdAt: '2026-08-20', updatedAt: '2026-08-25', activeVersionNo: 3,
      models: [], effectiveModels: [
        { modelType: 'LLM', resourceId: 'llm-a', name: 'DeepSeek', source: 'global', modelId: 'llm-a', overridden: false, overrides: {}, enabled: true, unavailableReason: null },
        { modelType: 'TTS', resourceId: 'tts-a', name: '火山语音', source: 'global', modelId: 'tts-a', overridden: false, overrides: {}, enabled: true, unavailableReason: null },
      ], boundDevices: [], memoryPolicy: {},
      skills: [{ skillId: 'skill-weather', versionMode: 'LATEST', fixedVersion: null, overrideJson: null, triggerPriority: 10, enabled: true }],
    }])
    if (path === '/companion/profiles/profile-a/model-options') return json([
      { id: 'llm-a', modelType: 'LLM', name: 'DeepSeek', source: 'global', providerCode: 'openai', enabled: true, isDefault: false, vendorName: 'DeepSeek', protocol: 'OpenAI', credentialStatus: 'configured', unavailableReason: null },
      { id: 'tts-a', modelType: 'TTS', name: '火山语音', source: 'global', providerCode: 'huoshan', enabled: true, isDefault: false, vendorName: '火山引擎', protocol: 'WebSocket', credentialStatus: 'configured', unavailableReason: null },
    ])
    if (path === '/api/v1/conversations' && route.request().method() === 'POST') return json({
      conversationId: 'conversation-a', agentId: 'profile-a', agentVersion: 3,
      streamUrl: 'ws://127.0.0.1:4173/mock-conversation', runtimeToken: 'runtime-token',
      expiresAt: '2026-08-26T12:00:00Z', inputModes: ['text', 'audio'], outputModes: ['text', 'audio'],
      publicMetadata: { agent_name: '小满', tts_voice_id: 'voice-a' },
    })
    return json([])
  })

  await page.routeWebSocket('**/mock-conversation', (socket) => {
    const event = (type: string, sequence: number, turnId: string | null, details: Record<string, unknown>) => JSON.stringify({
      type, conversation_id: 'conversation-a', turn_id: turnId, sequence,
      occurred_at: 1787673600000 + sequence, details,
    })
    socket.send(event('session.ready', 1, null, { agent_version: 3 }))
    socket.onMessage((raw) => {
      if (typeof raw !== 'string') return
      const message = JSON.parse(raw) as { type?: string; request_id?: string }
      if (message.type !== 'turn.text') return
      socket.send(event('turn.started', 2, 'turn-a', { request_id: message.request_id, input_mode: 'text' }))
      socket.send(event('llm.delta', 3, 'turn-a', { text: '你好，' }))
      socket.send(event('llm.delta', 4, 'turn-a', { text: '新版链路已经连接。' }))
      socket.send(event('tts.audio', 5, 'turn-a', { text: '你好，新版链路已经连接。', mime_type: 'audio/wav', data: 'UklGRg==' }))
      socket.send(event('turn.completed', 6, 'turn-a', { text: '你好，新版链路已经连接。' }))
    })
  })

  await page.addInitScript(() => localStorage.setItem('companion-console.session', JSON.stringify({ token: 'browser-token' })))
  await page.goto('/playground')
  await expect(page.getByRole('heading', { name: '操练场' })).toBeVisible()
  await page.getByRole('button', { name: '创建对话会话' }).click()
  await expect(page.getByText('已连接')).toBeVisible()
  await page.getByRole('textbox', { name: '消息' }).fill('测试新版接口')
  await page.getByRole('button', { name: '发送消息' }).click()
  await expect(page.getByText('你好，新版链路已经连接。', { exact: true })).toBeVisible()
  await expect(page.locator('audio')).toHaveCount(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy()
  await page.screenshot({ path: testInfo.outputPath(`playground-${testInfo.project.name}.png`), fullPage: true })
})
