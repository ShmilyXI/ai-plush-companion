import { describe, expect, it } from 'vitest'

import {
  canTestModelConnection,
  llmFieldDefault,
  llmFieldGuidance,
} from './modelEditorMetadata'

describe('modelEditorMetadata', () => {
  it('only enables the generic connection test for OpenAI LLM and VLLM providers', () => {
    expect(canTestModelConnection('LLM', 'openai')).toBe(true)
    expect(canTestModelConnection('VLLM', 'openai')).toBe(true)
    expect(canTestModelConnection('TTS', 'openai')).toBe(false)
    expect(canTestModelConnection('ASR', 'openai')).toBe(false)
    expect(canTestModelConnection('VAD', 'silero')).toBe(false)
    expect(canTestModelConnection('Embedding', 'openai')).toBe(true)
    expect(canTestModelConnection('Embedding', 'custom')).toBe(false)
    expect(canTestModelConnection('Memory', 'mem0ai')).toBe(false)
    expect(canTestModelConnection('Memory', 'tencentdb')).toBe(true)
    expect(canTestModelConnection('LLM', 'gemini')).toBe(false)
  })

  it('returns recommended defaults only for LLM fields with safe cross-provider values', () => {
    expect(llmFieldDefault('LLM', 'temperature')).toBe(0.7)
    expect(llmFieldDefault('LLM', 'max_tokens')).toBe(2048)
    expect(llmFieldDefault('LLM', 'top_p')).toBe(1)
    expect(llmFieldDefault('LLM', 'frequency_penalty')).toBe(0)
    expect(llmFieldDefault('LLM', 'top_k')).toBeUndefined()
    expect(llmFieldDefault('TTS', 'temperature')).toBeUndefined()
  })

  it('keeps top_k optional and explains the common starting value', () => {
    expect(llmFieldGuidance('LLM', 'top_k')).toEqual(expect.objectContaining({
      placeholder: '常见 40，不确定请留空',
    }))
    expect(llmFieldGuidance('TTS', 'top_k')).toBeUndefined()
  })
})
