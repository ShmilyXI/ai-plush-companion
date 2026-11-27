/**
 * 网页端角色设置里能选的声音来自哪些语音合成模型。
 *
 * 默认只有豆包语音 SeedTTS 2.0；部署时可以用 WEB_TTS_MODEL_IDS（逗号分隔的模型 ID）改，顺序就是显示顺序。
 * 只列开放的模型：角色现在用的模型不在其中时（例如以前选的 Edge），它的音色不再列出，换一个声音保存后角色就换到开放的模型上。
 * 没启用的模型会被跳过。
 */
export const DEFAULT_WEB_TTS_MODEL_IDS = ['TTS_DoubaoSeedTTS2']

const MODEL_ID = /^[A-Za-z0-9_]{1,32}$/

export function isModelId(value: unknown): value is string {
  return typeof value === 'string' && MODEL_ID.test(value)
}

export function offeredTtsModelIds(env: string | undefined = process.env.WEB_TTS_MODEL_IDS) {
  const configured = (env || '').split(',').map((item) => item.trim()).filter(isModelId)
  return configured.length ? configured : DEFAULT_WEB_TTS_MODEL_IDS
}

/** 按开放列表的顺序列出启用了的模型。去重。 */
export function voiceModelIds(offered: string[], enabled: Set<string>) {
  return [...new Set(offered.filter((id) => isModelId(id) && enabled.has(id)))]
}

/** 官方试听音频的地址。只接受 https，其他写法（例如旧数据里的 General）都当作没有。 */
export function demoUrl(value: string | null | undefined) {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.protocol === 'https:' ? url.toString() : null
  } catch { return null }
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
