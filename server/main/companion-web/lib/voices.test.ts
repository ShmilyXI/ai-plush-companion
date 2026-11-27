import { describe, expect, it } from 'vitest'
import { DEFAULT_WEB_TTS_MODEL_IDS, bindingsWithTts, demoUrl, offeredTtsModelIds, voiceModelIds } from './voices'

describe('offeredTtsModelIds', () => {
  it('defaults to Doubao SeedTTS 2.0 only', () => {
    expect(offeredTtsModelIds(undefined)).toEqual(DEFAULT_WEB_TTS_MODEL_IDS)
    expect(DEFAULT_WEB_TTS_MODEL_IDS).toEqual(['TTS_DoubaoSeedTTS2'])
  })

  it('reads a comma separated list and drops malformed ids', () => {
    expect(offeredTtsModelIds(' TTS_A, ../x ,TTS_B ')).toEqual(['TTS_A', 'TTS_B'])
  })
})

describe('voiceModelIds', () => {
  it('follows the offered order and skips disabled ones', () => {
    const enabled = new Set(['TTS_EdgeTTS', 'TTS_DoubaoSeedTTS2'])
    expect(voiceModelIds(['TTS_DoubaoSeedTTS2', 'TTS_EdgeTTS', 'TTS_Off'], enabled))
      .toEqual(['TTS_DoubaoSeedTTS2', 'TTS_EdgeTTS'])
  })

  it('lists only offered models, not the one a role used before', () => {
    expect(voiceModelIds(['TTS_DoubaoSeedTTS2'], new Set(['TTS_DoubaoSeedTTS2', 'TTS_EdgeTTS'])))
      .toEqual(['TTS_DoubaoSeedTTS2'])
  })
})

describe('demoUrl', () => {
  it('accepts https audio links only', () => {
    expect(demoUrl('https://cdn.example/demo.mp3')).toBe('https://cdn.example/demo.mp3')
    expect(demoUrl('http://cdn.example/demo.mp3')).toBeNull()
    expect(demoUrl('javascript:alert(1)')).toBeNull()
    expect(demoUrl('General')).toBeNull()
    expect(demoUrl(null)).toBeNull()
  })
})

describe('bindingsWithTts', () => {
  it('replaces only the TTS binding and keeps the others', () => {
    const models = [
      { modelType: 'LLM', source: 'global', resourceId: 'LLM_A' },
      { modelType: 'ASR', source: 'default', resourceId: null },
      { modelType: 'TTS', source: 'default', resourceId: null },
      { modelType: 'Memory', source: 'private', resourceId: 'P1' },
    ]
    expect(bindingsWithTts(models, 'TTS_DoubaoSeedTTS2')).toEqual([
      { modelType: 'LLM', source: 'global', resourceId: 'LLM_A' },
      { modelType: 'ASR', source: 'default' },
      { modelType: 'TTS', source: 'global', resourceId: 'TTS_DoubaoSeedTTS2' },
    ])
  })

  it('adds a TTS binding when the profile had none', () => {
    expect(bindingsWithTts(undefined, 'TTS_X')).toEqual([{ modelType: 'TTS', source: 'global', resourceId: 'TTS_X' }])
  })
})
