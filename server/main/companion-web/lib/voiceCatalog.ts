import type { WebVoice } from './manager'

/**
 * 角色设置里挑声音用的整理：从音色数据里读出场景、性别、年龄、语言和一句描述，供声音选择页分类筛选。
 *
 * 豆包的描述写成“女 · 青年。治愈感的通透女声”，性别和年龄从这里读；
 * Edge 的音色没有这一段，名字是“EdgeTTS女声-晓晓”，性别从名字读，显示时去掉前缀。
 */
export interface VoiceInfo {
  voice: WebVoice
  /** 显示用的名字：去掉“ 2.0”这类所有豆包音色都有的后缀和 Edge 的前缀。 */
  label: string
  scene: string
  gender: '女' | '男' | null
  age: string | null
  /** 语言，“普通话”算中文，“辽宁”“陕西”“粤语”这类算方言下面的一种。可能有几种。 */
  languages: string[]
  description: string
  /** 所属语音合成模型的名字。 */
  source: string
  /** 搜索用：名字、描述、场景、语言拼在一起的小写文字。 */
  text: string
}

export type VoiceFilterKey = 'scene' | 'gender' | 'age' | 'language' | 'source'
export type VoiceFilters = Partial<Record<VoiceFilterKey, string>> & { query?: string }

/** 场景的显示顺序和简称。不在这里的场景排在后面，照原样显示。 */
const SCENES: Array<[string, string]> = [
  ['通用场景', '通用'], ['角色扮演', '角色扮演'], ['有声阅读', '有声阅读'], ['视频配音', '视频配音'],
  ['教学场景', '教学'], ['客服场景', '客服'], ['趣味口音', '趣味口音'], ['外语音色', '外语'], ['其他', '其他'],
]
const AGES = ['儿童', '少年/少女', '青年', '中年', '老年']
const DIALECTS = new Set(['粤语', '辽宁', '陕西', '四川', '东北', '台湾', '河南', '上海'])
const LANGUAGE_ORDER = ['中文', '方言', '英语', '日语', '韩语']

const PROFILE = /^\s*(男|女)\s*·\s*([^。.]+?)\s*[。.]\s*/
const EDGE_NAME = /^EdgeTTS\s*(男|女)声\s*[-－·]\s*/

function normalizeLanguage(value: string) {
  const item = value.trim()
  if (!item) return null
  if (item === '普通话' || item === '中文' || item === '汉语') return '中文'
  if (DIALECTS.has(item) || item.endsWith('话') || item.endsWith('方言')) return '方言'
  if (item === '中英双语') return '中文'
  return item
}

function languagesOf(value: string | null | undefined) {
  const raw = (value || '').split(/[、,，/]/)
  const list = raw.map(normalizeLanguage).filter((item): item is string => Boolean(item))
  if ((value || '').includes('中英')) list.push('英语')
  return [...new Set(list)]
}

export function voiceInfo(voice: WebVoice): VoiceInfo {
  const remark = (voice.remark || '').trim()
  const profile = PROFILE.exec(remark)
  const edge = EDGE_NAME.exec(voice.name || '')
  const gender = (profile?.[1] || edge?.[1] || (/女/.test(voice.name || '') ? '女' : /男/.test(voice.name || '') ? '男' : null)) as VoiceInfo['gender']
  const age = profile && AGES.includes(profile[2]) ? profile[2] : null
  const description = profile ? remark.slice(profile[0].length).trim() : remark
  const label = (edge ? (voice.name || '').slice(edge[0].length) : voice.name || '').replace(/\s+2\.0$/, '').trim() || voice.name || voice.id
  // Edge 这类没写场景的音色都是日常说话用的，算通用。
  const scene = voice.scene?.trim() || '通用场景'
  const languages = languagesOf(voice.languages)
  const source = voice.ttsModelName || voice.ttsModelId || ''
  const text = [voice.name, label, description, scene, gender ? `${gender}声` : '', age, ...languages, source].join(' ').toLowerCase()
  return { voice, label, scene, gender, age, languages, description, source, text }
}

/** 一个音色属于某个筛选项的哪些值。 */
function valuesOf(info: VoiceInfo, key: VoiceFilterKey): string[] {
  switch (key) {
    case 'scene': return [info.scene]
    case 'gender': return info.gender ? [info.gender] : []
    case 'age': return info.age ? [info.age] : []
    case 'language': return info.languages
    case 'source': return info.source ? [info.source] : []
  }
}

function matches(info: VoiceInfo, filters: VoiceFilters, skip?: VoiceFilterKey) {
  for (const key of ['scene', 'gender', 'age', 'language', 'source'] as VoiceFilterKey[]) {
    const wanted = filters[key]
    if (!wanted || key === skip) continue
    if (!valuesOf(info, key).includes(wanted)) return false
  }
  const words = (filters.query || '').trim().toLowerCase().split(/\s+/).filter(Boolean)
  return words.every((word) => info.text.includes(word))
}

export function filterVoices(infos: VoiceInfo[], filters: VoiceFilters) {
  return infos.filter((info) => matches(info, filters))
}

export interface FacetOption { value: string; label: string; count: number }

function order(key: VoiceFilterKey, value: string) {
  const list = key === 'scene' ? SCENES.map(([name]) => name) : key === 'age' ? AGES : key === 'gender' ? ['女', '男'] : key === 'language' ? LANGUAGE_ORDER : []
  const index = list.indexOf(value)
  return index === -1 ? list.length : index
}

/** 筛选值显示成什么字：场景用简称，性别写成“女声”。 */
export function labelOf(key: VoiceFilterKey, value: string) {
  if (key === 'scene') return SCENES.find(([name]) => name === value)?.[1] ?? value
  if (key === 'gender') return `${value}声`
  if (key === 'age') return value === '少年/少女' ? '少年' : value
  return value
}

/**
 * 某个筛选项能选的值。只列出全部音色里出现过的值（选项不会随着筛选忽隐忽现），
 * 数字是在其他筛选条件下选它能看到几个。少的语言排在后面。
 */
export function facet(infos: VoiceInfo[], key: VoiceFilterKey, filters: VoiceFilters): FacetOption[] {
  const totals = new Map<string, number>()
  const counts = new Map<string, number>()
  for (const info of infos) {
    const hit = matches(info, filters, key)
    for (const value of valuesOf(info, key)) {
      totals.set(value, (totals.get(value) || 0) + 1)
      if (hit) counts.set(value, (counts.get(value) || 0) + 1)
    }
  }
  return [...totals.keys()]
    .sort((a, b) => order(key, a) - order(key, b) || (totals.get(b) || 0) - (totals.get(a) || 0))
    .map((value) => ({ value, label: labelOf(key, value), count: counts.get(value) || 0 }))
}

/** 卡片上的小标签：女声 · 青年 · 英语。中文不写，大多数音色都是。 */
export function voiceTags(info: VoiceInfo) {
  return [
    info.gender ? `${info.gender}声` : null,
    info.age ? labelOf('age', info.age) : null,
    ...info.languages.filter((item) => item !== '中文'),
  ].filter(Boolean).join(' · ')
}
