import { describe, expect, it } from 'vitest'

import { channelPresets, suggestCapabilities, supportedModelTypes } from './channelPresets'

describe('channel presets', () => {
  it('guesses what a model can do from its id, allowing several capabilities at once', () => {
    expect(suggestCapabilities('deepseek-chat', 'openai')).toEqual(['LLM'])
    expect(suggestCapabilities('qwen-plus', 'openai')).toEqual(['LLM'])
    expect(suggestCapabilities('doubao-1-5-pro-32k-250115', 'openai')).toEqual(['LLM'])
    expect(suggestCapabilities('text-embedding-v3', 'openai')).toEqual(['Embedding'])
    expect(suggestCapabilities('BAAI/bge-m3', 'openai')).toEqual(['Embedding'])
    for (const id of ['glm-4v-flash', 'qwen-vl-max', 'doubao-vision-pro-32k', 'qwen-omni-turbo', 'gpt-4o-mini']) {
      expect(suggestCapabilities(id, 'openai'), id).toEqual(['LLM', 'VLLM'])
    }
  })

  it('only offers conversation models for Ollama', () => {
    expect(supportedModelTypes('ollama')).toEqual(['LLM'])
    expect(suggestCapabilities('nomic-embed-text', 'ollama')).toEqual(['LLM'])
    expect(suggestCapabilities('llava', 'ollama')).toEqual(['LLM'])
    expect(supportedModelTypes('openai')).toEqual(['LLM', 'VLLM', 'Embedding'])
  })

  it('keeps preset codes unique and addresses free of trailing slashes', () => {
    expect(new Set(channelPresets.map((preset) => preset.code)).size).toBe(channelPresets.length)
    for (const preset of channelPresets) {
      expect(preset.baseUrl.endsWith('/')).toBe(false)
    }
  })
})
