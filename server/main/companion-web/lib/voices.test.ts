import { describe, expect, it } from 'vitest'
import { DEFAULT_WEB_TTS_MODEL_IDS, bindingsWithTts, offeredTtsModelIds, voiceModelIds } from './voices'

describe('offeredTtsModelIds', () => {
  it('defaults to Edge and Doubao SeedTTS 2.0', () => {
    expect(offeredTtsModelIds(undefined)).toEqual(DEFAULT_WEB_TTS_MODEL_IDS)
    expect(DEFAULT_WEB_TTS_MODEL_IDS).toContain('TTS_DoubaoSeedTTS2')
  })

  it('reads a comma separated list and drops malformed ids', () => {
    expect(offeredTtsModelIds(' TTS_A, ../x ,TTS_B ')).toEqual(['TTS_A', 'TTS_B'])
  })
})

describe('voiceModelIds', () => {
  it('keeps the current model first and skips disabled ones', () => {
    const enabled = new Set(['TTS_EdgeTTS', 'TTS_DoubaoSeedTTS2'])
    expect(voiceModelIds('TTS_EdgeTTS', ['TTS_EdgeTTS', 'TTS_DoubaoSeedTTS2', 'TTS_Off'], enabled))
      .toEqual(['TTS_EdgeTTS', 'TTS_DoubaoSeedTTS2'])
  })

  it('keeps the current model even when it is not offered', () => {
    expect(voiceModelIds('TTS_Old', ['TTS_DoubaoSeedTTS2'], new Set(['TTS_DoubaoSeedTTS2'])))
      .toEqual(['TTS_Old', 'TTS_DoubaoSeedTTS2'])
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
