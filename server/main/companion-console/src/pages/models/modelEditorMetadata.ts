import type { ModelType } from '../../api/xiaozhiModels'

interface LlmFieldGuidance {
  help: string
  defaultValue?: number | boolean
  placeholder?: string
}

const LLM_FIELD_GUIDANCE: Record<string, LlmFieldGuidance> = {
  stream_enabled: {
    defaultValue: true,
    help: '打开后模型边生成边交给语音合成，能更早开口。关闭后要等整段回答生成完才说，等待会更久。',
  },
  thinking_enabled: {
    defaultValue: false,
    help: '语音聊天建议关闭，响应更快。打开后复杂推理可能更稳，但首句会更慢。',
  },
  tools_enabled: {
    defaultValue: true,
    help: '打开后才能调音量、亮度、相机等设备能力。普通聊天会自动少带工具，减少等待。',
  },
  first_content_timeout: {
    defaultValue: 8,
    help: '超过这个时间还没有可播放文字就报错，避免板子一直显示说话中。建议 8 秒。',
  },
  temperature: {
    defaultValue: 1.3,
    help: '控制回答有多活。调低会更稳、更像固定答案；调高会更有变化，也更容易跑偏。DeepSeek 普通对话建议 1.3。',
  },
  max_tokens: {
    defaultValue: 2048,
    help: '限制一次最多回答多长。调低会更短，太低可能说到一半被截断；调高会更长，也更慢、更费额度。建议 2048。',
  },
  top_p: {
    defaultValue: 1,
    help: '控制模型会考虑多少种说法。调低会更保守；调高会更多样。建议保持 1，通常只调温度，不要两项一起大改。',
  },
  top_k: {
    placeholder: '常见 40，不确定请留空',
    help: '只让模型从最有可能的若干个词里选。数值低更集中，数值高更多样。不是所有接口都支持，常见起点是 40，不确定就留空。',
  },
  frequency_penalty: {
    defaultValue: 0,
    help: '减少同一个词反复出现。调高会少重复，太高会显得生硬。建议 0。',
  },
}

export function canTestModelConnection(modelType: ModelType, providerCode?: string) {
  const provider = providerCode?.toLowerCase()
  return ((modelType === 'LLM' || modelType === 'VLLM' || modelType === 'Embedding') && provider === 'openai')
    || (modelType === 'Memory' && provider === 'tencentdb')
}

export function llmFieldGuidance(modelType: ModelType, key: string) {
  return modelType === 'LLM' ? LLM_FIELD_GUIDANCE[key] : undefined
}

export function llmFieldDefault(modelType: ModelType, key: string) {
  return llmFieldGuidance(modelType, key)?.defaultValue
}
