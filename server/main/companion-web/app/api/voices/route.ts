import { NextResponse } from 'next/server'
import { respond } from '@/lib/respond'
import { callManager, type ManagerResult } from '@/lib/server-manager'
import { isModelId, offeredTtsModelIds, voiceModelIds } from '@/lib/voices'

interface Model { id?: string; modelName?: string }
interface Voice {
  id?: string; name?: string; ttsModelId?: string | null; voiceDemo?: string | null; languages?: string | null; scene?: string | null
}
type VoicePage = { list?: Voice[]; total?: number }

const PAGE_SIZE = 100
const MAX_PAGES = 10

/** 一个模型的全部音色。接口一页最多 100 个，豆包这类模型有几百个，所以逐页读完。 */
async function voicesOf(model: string): Promise<ManagerResult<VoicePage>> {
  const list: Voice[] = []
  for (let page = 1; page <= MAX_PAGES; page++) {
    const result = await callManager<VoicePage>(
      `/api/v1/voices?ttsModelId=${encodeURIComponent(model)}&page=${page}&limit=${PAGE_SIZE}`)
    if (!result.ok) return page === 1 ? result : { ...result, ok: true, data: { list } }
    const items = Array.isArray(result.data?.list) ? result.data.list : []
    list.push(...items)
    const total = typeof result.data?.total === 'number' ? result.data.total : list.length
    if (items.length < PAGE_SIZE || list.length >= total) break
  }
  return { ok: true, status: 200, code: 0, message: '', data: { list } }
}

/**
 * 角色设置里能选的声音：角色现在用的模型的音色，加上网页端开放的其他语音合成模型（见 lib/voices.ts）的音色。
 * 每个音色带上所属模型，选了别的模型的音色时，保存会一起换模型。
 */
export async function GET(request: Request) {
  const current = new URL(request.url).searchParams.get('ttsModelId') || ''
  if (!isModelId(current)) return NextResponse.json({ data: [] })
  const models = await callManager<Model[]>('/api/v1/models?type=TTS')
  const enabled = new Map((models.ok && Array.isArray(models.data) ? models.data : [])
    .filter((model) => model.id).map((model) => [model.id as string, model.modelName || model.id as string]))
  const ids = voiceModelIds(current, offeredTtsModelIds(), new Set(enabled.keys()))
  const results = await Promise.all(ids.map(voicesOf))
  // 角色现在的模型读不出来就照旧报错；其他模型读不出来只是少几个选项。
  const own = results[ids.indexOf(current)]
  if (own && !own.ok) return respond(own, '音色加载失败')
  const voices = results.flatMap((result, index) => {
    if (!result.ok || !Array.isArray(result.data?.list)) return []
    return result.data.list.map((voice) => ({
      ...voice,
      ttsModelId: voice.ttsModelId || ids[index],
      ttsModelName: enabled.get(ids[index]) || ids[index],
    }))
  })
  return NextResponse.json({ data: voices })
}
