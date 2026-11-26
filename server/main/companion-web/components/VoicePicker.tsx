'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeft, Check, Loader2, Search, Square, Volume2, X } from 'lucide-react'
import type { WebVoice } from '../lib/manager'
import { facet, filterVoices, voiceInfo, voiceTags, type VoiceFilterKey, type VoiceFilters } from '../lib/voiceCatalog'

interface VoicePickerProps {
  voices: WebVoice[]
  selectedId: string
  /** 正在试听的音色和状态。试听由角色设置管，这样关掉选择页后试听按钮的状态还对得上。 */
  preview: { id: string; state: 'loading' | 'playing' } | null
  previewError: string
  onPreview: (id: string) => void
  onSelect: (id: string) => void
  onClose: () => void
}

/** 筛选行：场景放最上面做主分类，其余几行是可选的细分。 */
const ROWS: Array<{ key: VoiceFilterKey; title: string }> = [
  { key: 'gender', title: '性别' },
  { key: 'age', title: '年龄' },
  { key: 'language', title: '语言' },
  { key: 'source', title: '来源' },
]

/**
 * 声音选择页：豆包有几百个音色，一个下拉框翻不过来。
 * 按场景分页签，再按性别、年龄、语言细分，也可以直接搜名字和描述。每个音色都能先试听再选。
 * 点一个音色就选上并回到角色设置。
 */
export default function VoicePicker({ voices, selectedId, preview, previewError, onPreview, onSelect, onClose }: VoicePickerProps) {
  const infos = useMemo(() => voices.map(voiceInfo), [voices])
  const [filters, setFilters] = useState<VoiceFilters>({})
  const results = useMemo(() => filterVoices(infos, filters), [infos, filters])
  const selected = infos.find((info) => info.voice.id === selectedId)
  const list = useRef<HTMLDivElement | null>(null)

  // 打开时把选中的音色滚到看得见的地方。
  // 只滚这一页的列表，不用 scrollIntoView：它会连带滚动下面的角色设置。
  useEffect(() => {
    const body = list.current
    const card = body?.querySelector('.voice-card[data-checked]')
    if (!body || !card) return
    const offset = card.getBoundingClientRect().top - body.getBoundingClientRect().top
    body.scrollTop += offset - body.clientHeight / 2 + card.clientHeight / 2
  }, [])

  function set(key: VoiceFilterKey | 'query', value: string | undefined) {
    setFilters((current) => ({ ...current, [key]: value }))
    list.current?.scrollTo?.({ top: 0 })
  }

  const scenes = facet(infos, 'scene', filters)
  const filtered = Boolean(filters.query || filters.gender || filters.age || filters.language || filters.source || filters.scene)
  const sceneTotal = filterVoices(infos, { ...filters, scene: undefined }).length

  return <div className="voice-picker" role="dialog" aria-modal="true" aria-label="选择声音">
    <header className="editor-bar voice-picker-bar">
      <button type="button" className="glass-button" onClick={onClose} aria-label="返回角色设置"><ArrowLeft size={22} /></button>
      <div className="voice-picker-title">
        <h1>选择声音</h1>
        <p>{selected ? `现在用的是 ${selected.label}` : `共 ${infos.length} 个声音`}</p>
      </div>
    </header>

    <div className="voice-picker-body" ref={list}>
    {/* 搜索和场景钉在顶上；细分的筛选跟着列表一起滚走，给音色多留地方。 */}
    <div className="voice-picker-sticky">
      <label className="voice-search">
        <Search size={18} aria-hidden="true" />
        <input
          type="search" value={filters.query || ''} placeholder="搜名字或感觉，比如“温柔”“御姐”“磁性”" aria-label="搜索声音"
          onChange={(event) => set('query', event.target.value)}
        />
        {filters.query && <button type="button" className="tool-button" aria-label="清除搜索" onClick={() => set('query', undefined)}><X size={16} /></button>}
      </label>

      <div className="voice-scenes" role="radiogroup" aria-label="场景">
        <button type="button" role="radio" aria-checked={!filters.scene} aria-label={`全部，${sceneTotal} 个`} onClick={() => set('scene', undefined)}>
          全部<span>{sceneTotal}</span>
        </button>
        {scenes.map((item) => <button
          key={item.value} type="button" role="radio" aria-checked={filters.scene === item.value} aria-label={`${item.label}，${item.count} 个`}
          onClick={() => set('scene', filters.scene === item.value ? undefined : item.value)}
        >{item.label}<span>{item.count}</span></button>)}
      </div>
    </div>

    <div className="voice-filter-rows">
      {ROWS.map(({ key, title }) => {
        const options = facet(infos, key, filters)
        // 只有一种值的筛选（例如只开放了一个模型）没有意义，不显示。
        if (options.length < 2) return null
        return <div key={key} className="voice-filter-row" role="radiogroup" aria-label={title}>
          <span className="voice-filter-title">{title}</span>
          <div className="voice-filter-chips">
            <button type="button" role="radio" className="chip" aria-checked={!filters[key]} onClick={() => set(key, undefined)}>不限</button>
            {options.map((item) => <button
              key={item.value} type="button" role="radio" className="chip" aria-checked={filters[key] === item.value}
              disabled={!item.count && filters[key] !== item.value}
              onClick={() => set(key, filters[key] === item.value ? undefined : item.value)}
            >{item.label}</button>)}
          </div>
        </div>
      })}
    </div>

      <div className="voice-result-bar">
        <span>{filtered ? `找到 ${results.length} 个` : `全部 ${results.length} 个`}</span>
        {filtered && <button type="button" className="link-button" onClick={() => setFilters({})}>清除筛选</button>}
      </div>
      {previewError && <p className="field-note field-alert" role="alert">{previewError}</p>}
      {results.length === 0
        ? <p className="memory-empty">没有符合条件的声音，换个词或少选几个条件试试。</p>
        : <div className="voice-grid" role="radiogroup" aria-label="声音">
          {results.map((info) => {
            const id = info.voice.id
            const checked = id === selectedId
            const playing = preview?.id === id
            const tags = voiceTags(info)
            return <div key={id} className="voice-card" data-checked={checked || undefined}>
              <button
                type="button" role="radio" aria-checked={checked} className="voice-card-pick" onClick={() => onSelect(id)}
                aria-label={info.label} aria-description={[tags, info.description].filter(Boolean).join('。') || undefined}
              >
                <span className="voice-card-name">{info.label}{checked && <Check size={16} aria-hidden="true" />}</span>
                {tags && <span className="voice-card-tags">{tags}</span>}
                {info.description && <span className="voice-card-desc">{info.description}</span>}
              </button>
              <button
                type="button" className="voice-card-play" onClick={() => onPreview(id)}
                aria-label={playing ? `停止试听${info.label}` : `试听${info.label}`}
              >
                {playing && preview?.state === 'loading' ? <Loader2 size={18} className="spin" aria-hidden="true" />
                  : playing ? <Square size={14} aria-hidden="true" /> : <Volume2 size={18} aria-hidden="true" />}
              </button>
            </div>
          })}
        </div>}
    </div>
  </div>
}
