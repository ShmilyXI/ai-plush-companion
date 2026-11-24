/**
 * 网页端角色设置里能选的声音来自哪些语音合成模型。
 *
 * 默认是 Edge 和豆包语音 SeedTTS 2.0；部署时可以用 WEB_TTS_MODEL_IDS（逗号分隔的模型 ID）改。
 * 角色现在用的模型总在列表里，没启用的模型会被跳过。
 */
export const DEFAULT_WEB_TTS_MODEL_IDS = ['TTS_EdgeTTS', 'TTS_DoubaoSeedTTS2']

const MODEL_ID = /^[A-Za-z0-9_]{1,32}$/

export function isModelId(value: unknown): value is string {
  return typeof value === 'string' && MODEL_ID.test(value)
}

export function offeredTtsModelIds(env: string | undefined = process.env.WEB_TTS_MODEL_IDS) {
  const configured = (env || '').split(',').map((item) => item.trim()).filter(isModelId)
  return configured.length ? configured : DEFAULT_WEB_TTS_MODEL_IDS
}

/** 角色现在的模型排第一，其后是启用了的可选模型，去重。 */
export function voiceModelIds(current: string, offered: string[], enabled: Set<string>) {
  const ids = [current, ...offered.filter((id) => enabled.has(id))].filter(isModelId)
  return [...new Set(ids)]
}

export interface ProfileModelBinding {
  modelType?: string
  source?: string
  resourceId?: string | null
}

/**
 * 换语音合成模型时要提交的整组模型绑定。manager-api 按这组绑定整体替换，
 * 所以其他用途原样带回去（不带 overrides，服务端会保留原来的），只把 TTS 换成选中的系统模型。
 * 个人模型已经停用，提交会被拒绝，所以不带回去。
 */
export function bindingsWithTts(models: ProfileModelBinding[] | null | undefined, ttsModelId: string) {
  const kept = (models || [])
    .filter((item) => item.modelType && item.modelType !== 'TTS' && item.source !== 'private')
    .map((item) => ({
      modelType: item.modelType,
      source: item.source,
      ...(item.source === 'default' ? {} : { resourceId: item.resourceId ?? null }),
    }))
  return [...kept, { modelType: 'TTS', source: 'global', resourceId: ttsModelId }]
}
