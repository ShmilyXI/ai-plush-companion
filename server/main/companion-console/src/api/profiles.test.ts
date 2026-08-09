import { expect, it, vi } from 'vitest'

import http from './http'
import { getProfile, updateProfile } from './profiles'

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
