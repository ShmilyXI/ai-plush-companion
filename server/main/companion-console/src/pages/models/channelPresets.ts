import type { ChannelModelType, ChannelProviderCode } from '../../api/modelChannels'

export interface ChannelPreset {
  code: string
  name: string
  providerCode: ChannelProviderCode
  baseUrl: string
  /** 本地服务通常不需要密钥。 */
  keyRequired: boolean
}

/**
 * 常见服务商的接口地址，只用于新建连接时预填，保存后以连接自己的内容为准。
 * 地址与管理服务预置模型里的一致。
 */
export const channelPresets: ChannelPreset[] = [
  { code: 'deepseek', name: 'DeepSeek', providerCode: 'openai', baseUrl: 'https://api.deepseek.com', keyRequired: true },
  { code: 'aliyun-bailian', name: '阿里云百炼', providerCode: 'openai', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', keyRequired: true },
  { code: 'volcengine-ark', name: '火山方舟（豆包）', providerCode: 'openai', baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', keyRequired: true },
  { code: 'zhipu', name: '智谱 AI', providerCode: 'openai', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', keyRequired: true },
  { code: 'ollama', name: 'Ollama（本机）', providerCode: 'ollama', baseUrl: 'http://localhost:11434', keyRequired: false },
  { code: 'lm-studio', name: 'LM Studio（本机）', providerCode: 'openai', baseUrl: 'http://localhost:1234/v1', keyRequired: false },
  { code: 'custom-openai', name: '其他 OpenAI 兼容服务', providerCode: 'openai', baseUrl: '', keyRequired: true },
]

export const channelTypeLabels: Record<ChannelModelType, string> = {
  LLM: '对话',
  VLLM: '视觉',
  Embedding: '向量',
}

export const channelProviderLabels: Record<ChannelProviderCode, string> = {
  openai: 'OpenAI 兼容',
  ollama: 'Ollama',
}

/** 每种接口协议能提供的用途。Ollama 目前只接入了对话模型。 */
export function supportedModelTypes(providerCode: ChannelProviderCode): ChannelModelType[] {
  return providerCode === 'ollama' ? ['LLM'] : ['LLM', 'VLLM', 'Embedding']
}

/**
 * 服务商的模型列表不说明模型能做什么，这里按代号里的常见写法猜它的能力，导入后可以随时改。
 * 一个模型可以同时有多种能力，例如能看图的对话模型同时支持对话和视觉。
 */
export function suggestCapabilities(modelId: string, providerCode: ChannelProviderCode): ChannelModelType[] {
  if (providerCode === 'ollama') return ['LLM']
  const id = modelId.toLowerCase()
  if (/embed|(^|[-_/])bge([-_]|$)|m3e|text2vec/.test(id)) return ['Embedding']
  if (/vision|llava|omni|multimodal|gemini|(^|[-_/.])vl([-_/.]|$)|(^|[-_/])qvq|\d+v([-_]|$)|(^|[-_/.])4o([-_/.]|$)/.test(id)) {
    return ['LLM', 'VLLM']
  }
  return ['LLM']
}
