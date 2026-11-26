import { describe, expect, it } from 'vitest'
import { facet, filterVoices, voiceInfo, voiceTags } from './voiceCatalog'

const doubao = (id: string, name: string, remark: string, scene: string, languages = '中文') => ({
  id, name, remark, scene, languages, ttsModelId: 'TTS_DoubaoSeedTTS2', ttsModelName: '豆包语音',
})
const VOICES = [
  doubao('d1', '团子 2.0', '女 · 青年。节奏匀稳、自带治愈感的通透女声', '通用场景'),
  doubao('d2', '温柔妈妈 2.0', '女 · 中年。语调舒缓，自带母性柔光的治愈女声', '通用场景'),
  doubao('d3', '嚣张小哥 2.0', '男 · 青年。张扬跋扈的小哥', '角色扮演'),
  doubao('d4', 'Ethan 2.0', '男 · 青年。温文尔雅的大叔，擅长英语', '外语音色', '英语'),
  { id: 'e1', name: 'EdgeTTS女声-晓晓', languages: '普通话', ttsModelId: 'TTS_EdgeTTS', ttsModelName: 'Edge' },
  { id: 'e2', name: 'EdgeTTS男声-香港万龙', languages: '粤语', remark: 'Friendly, Positive', ttsModelId: 'TTS_EdgeTTS', ttsModelName: 'Edge' },
  { id: 'e3', name: 'EdgeTTS女声-艾玛（中英双语）', languages: '中英双语', ttsModelId: 'TTS_EdgeTTS', ttsModelName: 'Edge' },
]
const infos = VOICES.map(voiceInfo)

describe('voiceInfo', () => {
  it('reads gender, age and description from a Doubao remark and drops the 2.0 suffix', () => {
    expect(infos[0]).toMatchObject({ label: '团子', gender: '女', age: '青年', description: '节奏匀稳、自带治愈感的通透女声', scene: '通用场景', languages: ['中文'] })
  })

  it('reads gender from an Edge name, drops its prefix and maps regional languages', () => {
    expect(infos[4]).toMatchObject({ label: '晓晓', gender: '女', age: null, scene: '通用场景', languages: ['中文'] })
    expect(infos[5]).toMatchObject({ label: '香港万龙', gender: '男', languages: ['方言'], description: 'Friendly, Positive' })
    expect(infos[6].languages).toEqual(['中文', '英语'])
  })

  it('tags a card without repeating Chinese', () => {
    expect(voiceTags(infos[0])).toBe('女声 · 青年')
    expect(voiceTags(infos[3])).toBe('男声 · 青年 · 英语')
  })
})

describe('filterVoices', () => {
  it('combines filters and matches every search word', () => {
    expect(filterVoices(infos, { gender: '女', scene: '通用场景' }).map((info) => info.voice.id)).toEqual(['d1', 'd2', 'e1', 'e3'])
    expect(filterVoices(infos, { query: '治愈 中年' }).map((info) => info.voice.id)).toEqual(['d2'])
    expect(filterVoices(infos, { language: '英语' }).map((info) => info.voice.id)).toEqual(['d4', 'e3'])
  })
})

describe('facet', () => {
  it('lists scenes in a fixed order with counts under the other filters', () => {
    expect(facet(infos, 'scene', { gender: '男' })).toEqual([
      { value: '通用场景', label: '通用', count: 1 },
      { value: '角色扮演', label: '角色扮演', count: 1 },
      { value: '外语音色', label: '外语', count: 1 },
    ])
  })

  it('ignores its own filter when counting', () => {
    expect(facet(infos, 'gender', { gender: '女' }).map((item) => [item.label, item.count])).toEqual([['女声', 4], ['男声', 3]])
  })
})
