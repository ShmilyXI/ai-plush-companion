import { expect, it, vi } from 'vitest'

import http from './http'
import { getProfile, getProfileVersion, updateProfile } from './profiles'

it('reads a profile snapshot detail for draft restoration', async () => {
  vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: {
    id: 'snapshot-2', versionNo: 2, source: 'companion-update', createdAt: '2026-07-29T10:00:00Z',
    snapshotData: {
      agentName: '旧版小满', relationMode: 'lover', userAddress: '队长', personality: '活泼', systemPrompt: '旧版提示词',
      companionCueConfig: '{"laugh":"config/assets/companion/laugh.wav"}',
      screenExpressionEnabled: 0, cameraPreferenceEnabled: 1,
      llmModelId: 'llm-old', asrModelId: null, ttsModelId: 'tts-old', vadModelId: null,
      vllmModelId: null, memModelId: 'memory-old', ttsVoiceId: 'voice-old',
    },
  } }, config: {} })

  await expect(getProfileVersion('profile/a', 'snapshot/2')).resolves.toMatchObject({
    id: 'snapshot-2', versionNo: 2,
    snapshot: {
      name: '旧版小满', relationMode: 'lover', userAddress: '队长', personality: '活泼', systemPrompt: '旧版提示词',
      companionCues: { laugh: true, sigh: false, hesitate: false, breathe: false },
      screenExpressionEnabled: false, cameraPreferenceEnabled: true, ttsVoiceId: 'voice-old',
      modelResourceIds: { LLM: 'llm-old', ASR: null, TTS: 'tts-old', VAD: null, VLLM: null, Memory: 'memory-old' },
    },
  })
  expect(http.get).toHaveBeenCalledWith('/agent/profile%2Fa/snapshots/snapshot%2F2', undefined)
})

it('accepts legacy profile snapshots whose companion fields were not recorded yet', async () => {
  vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: {
    id: 'snapshot-legacy', versionNo: 1, source: 'initial', createdAt: '2026-01-01T00:00:00Z',
    snapshotData: {
      agentName: '旧角色', relationMode: null, userAddress: null, personality: null, systemPrompt: '旧提示词',
      companionCueConfig: null, screenExpressionEnabled: null, cameraPreferenceEnabled: null, ttsVoiceId: null,
      llmModelId: null, asrModelId: null, ttsModelId: null, vadModelId: null, vllmModelId: null, memModelId: null,
    },
  } }, config: {} })

  await expect(getProfileVersion('profile-a', 'snapshot-legacy')).resolves.toMatchObject({ snapshot: {
    name: '旧角色', relationMode: null, userAddress: null, personality: null, systemPrompt: '旧提示词',
    companionCues: null, screenExpressionEnabled: null, cameraPreferenceEnabled: null,
  } })
})

it('parses unavailable migration metadata for bindings and effective models', async () => {
  vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: {
    id: 'profile-a', name: '小满', relationMode: 'friend', userAddress: null, personality: null,
    systemPrompt: null, companionCueConfig: null, screenExpressionEnabled: 1, cameraPreferenceEnabled: 1,
    templateId: null, llmModelId: null, llmModelName: null, ttsModelId: null, ttsModelName: null,
    ttsVoiceId: null, ttsVoiceName: null, ttsLanguage: null, createdAt: null, updatedAt: null,
    models: [{ modelType: 'LLM', source: 'private', resourceId: 'private-old', name: '旧个人模型',
      overrides: {}, enabled: false, unavailableReason: '旧个人模型已停用，请重新选择' }],
    effectiveModels: [{ modelType: 'LLM', source: 'private', resourceId: 'private-old', name: '旧个人模型',
      modelId: null, overridden: false, overrides: {}, enabled: false,
      unavailableReason: '旧个人模型已停用，请重新选择' }],
  } }, config: {} })

  const profile = await getProfile('profile-a')

  expect(profile.models[0]).toMatchObject({ name: '旧个人模型', enabled: false,
    unavailableReason: '旧个人模型已停用，请重新选择' })
  expect(profile.effectiveModels[0]).toMatchObject({ name: '旧个人模型', enabled: false,
    unavailableReason: '旧个人模型已停用，请重新选择' })
})

it('omits model and voice fields when preserving untouched migration data', async () => {
  vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: null }, config: {} })

  await updateProfile('profile-a', {
    name: '新名称', relationMode: 'friend', userAddress: '', personality: '', systemPrompt: '',
    companionCues: { laugh: false, sigh: false, hesitate: false, breathe: false },
    screenExpressionEnabled: true, cameraPreferenceEnabled: true,
  })

  const payload = vi.mocked(http.put).mock.calls[0][1]
  expect(payload).not.toHaveProperty('models')
  expect(payload).not.toHaveProperty('ttsVoiceId')
})

it('updates a voice without sending preserved private model bindings', async () => {
  vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: null }, config: {} })

  await updateProfile('profile-a', {
    name: '小满', relationMode: 'friend', userAddress: '', personality: '', systemPrompt: '', ttsVoiceId: 'voice-b',
    companionCues: { laugh: false, sigh: false, hesitate: false, breathe: false },
    screenExpressionEnabled: true, cameraPreferenceEnabled: true,
  })

  const payload = vi.mocked(http.put).mock.calls[0][1]
  expect(payload).toHaveProperty('ttsVoiceId', 'voice-b')
  expect(payload).not.toHaveProperty('models')
})

it('sends complete writable bindings only after migration is resolved', async () => {
  vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: null }, config: {} })

  await updateProfile('profile-a', {
    name: '小满', relationMode: 'friend', userAddress: '', personality: '', systemPrompt: '', ttsVoiceId: 'voice-a',
    companionCues: { laugh: false, sigh: false, hesitate: false, breathe: false },
    screenExpressionEnabled: true, cameraPreferenceEnabled: true,
    models: [
      { modelType: 'TTS', source: 'global', resourceId: 'tts-a' },
      { modelType: 'LLM', source: 'global', resourceId: 'llm-a', name: '自然对话',
        overrides: { model: 'legacy' }, enabled: true, unavailableReason: null },
      { modelType: 'ASR', source: 'default', overrides: { language: 'zh' }, enabled: true },
    ],
  })

  expect(http.put).toHaveBeenCalledWith('/companion/profiles/profile-a', expect.objectContaining({
    ttsVoiceId: 'voice-a',
    models: [
      { modelType: 'TTS', source: 'global', resourceId: 'tts-a' },
      { modelType: 'LLM', source: 'global', resourceId: 'llm-a' },
      { modelType: 'ASR', source: 'default' },
    ],
  }), undefined)
})

it('sends agent Skill bindings with version policy and sanitized overrides', async () => {
  vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: null }, config: {} })

  await updateProfile('profile-a', {
    name: '小满', relationMode: 'friend', userAddress: '', personality: '', systemPrompt: '',
    companionCues: { laugh: false, sigh: false, hesitate: false, breathe: false },
    screenExpressionEnabled: true, cameraPreferenceEnabled: true,
    skills: [{ skillId: 'skill-camera', versionMode: 'FIXED', fixedVersion: 3,
      overrideJson: '{"token":"***"}', triggerPriority: 10, enabled: true }],
  })

  expect(http.put).toHaveBeenCalledWith('/companion/profiles/profile-a', expect.objectContaining({
    skills: [{ skillId: 'skill-camera', versionMode: 'FIXED', fixedVersion: 3,
      overrideJson: '{"token":"***"}', triggerPriority: 10, enabled: true }],
  }), undefined)
})

it('does not infer a voice update from an unrelated model update', async () => {
  vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: null }, config: {} })

  await updateProfile('profile-a', {
    name: '小满', relationMode: 'friend', userAddress: '', personality: '', systemPrompt: '',
    companionCues: { laugh: false, sigh: false, hesitate: false, breathe: false },
    screenExpressionEnabled: true, cameraPreferenceEnabled: true,
    models: [
      { modelType: 'LLM', source: 'global', resourceId: 'llm-b' },
      { modelType: 'TTS', source: 'default' },
    ],
  })

  const payload = vi.mocked(http.put).mock.calls[0][1]
  expect(payload).toHaveProperty('models')
  expect(payload).not.toHaveProperty('ttsVoiceId')
})

it('preserves a selected voice when submitting a default TTS binding', async () => {
  vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: null }, config: {} })

  await updateProfile('profile-a', {
    name: '小满', relationMode: 'friend', userAddress: '', personality: '', systemPrompt: '', ttsVoiceId: 'voice-default',
    companionCues: { laugh: false, sigh: false, hesitate: false, breathe: false },
    screenExpressionEnabled: true, cameraPreferenceEnabled: true,
    models: [{ modelType: 'TTS', source: 'default' }],
  })

  const payload = vi.mocked(http.put).mock.calls[0][1]
  expect(payload).toHaveProperty('ttsVoiceId', 'voice-default')
  expect(payload).toHaveProperty('models', [{ modelType: 'TTS', source: 'default' }])
})

it('rejects unresolved private bindings instead of silently deleting them', async () => {
  const put = vi.spyOn(http, 'put')

  await expect(updateProfile('profile-a', {
    name: '小满', relationMode: 'friend', userAddress: '', personality: '', systemPrompt: '', ttsVoiceId: '',
    companionCues: { laugh: false, sigh: false, hesitate: false, breathe: false },
    screenExpressionEnabled: true, cameraPreferenceEnabled: true,
    models: [{ modelType: 'LLM', source: 'private', resourceId: 'private-old' }],
  })).rejects.toThrow('请先替换所有已停用的个人模型')

  expect(put).not.toHaveBeenCalled()
})
