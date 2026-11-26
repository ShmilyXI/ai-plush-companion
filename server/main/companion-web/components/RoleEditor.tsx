'use client'

import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, ChevronRight, Lock, Loader2, Square, Volume2, X } from 'lucide-react'
import { managerJson, type WebVoice } from '../lib/manager'
import { demoUrl } from '../lib/voices'
import { voiceInfo, voiceTags } from '../lib/voiceCatalog'
import VoicePicker from './VoicePicker'
import LookPicker from './LookPicker'
import { LOOK_COLORS, LOOK_KINDS, PREVIEW_NAME, currentLook, currentOutfit, hasPresetLook, roleLook, roleOutfits, saveLook, saveOutfit, setCustomLook, setOutfit, setPreviewOutfit, type CustomLook } from '../lib/roles'
import dynamic from 'next/dynamic'
import RoleCharacter, { characterKind, characterStyle } from './RoleCharacter'
import { DEFAULT_CAST, MOWA_DEMO_MS, MOWA_DEMO_STATES } from '../lib/mowa'
import type { CharacterState } from '@mowa-bot/engine'

// Mowa Bot 的角色表只在选了 Mowa Bot 时才下载。
const MowaLookSummary = dynamic(() => import('./MowaLookPicker').then((module) => module.MowaLookSummary), { ssr: false })
import RoleFigure from './RoleFigure'

interface RoleSettings {
  id: string; name: string; userAddress: string; personality: string; systemPrompt: string
  fromPreset: boolean; ttsModelId: string | null; ttsVoiceId: string
}

interface Memory { id: string; content: string; source?: string }

interface RoleEditorProps {
  agentId: string
  /** 这个角色现在的名字，用来找它现在的形象。 */
  agentName: string
  /** 刚新建的空白角色：标题不同，关掉时提醒还没起名字。 */
  fresh?: boolean
  onClose: () => void
  /** 保存成功。页面要重新读一遍角色，并让正在进行的对话换上新的设置。 */
  onSaved: () => void
  onDeleted: () => void
  /** 和它的对话记录已经清空，对话页上显示的消息也要跟着清掉。 */
  onHistoryCleared?: () => void
}

/** 点一下就加进性格描述里的词。 */
const TRAITS = ['温柔', '直接', '话多', '爱开玩笑', '冷静', '好奇心重', '有点毒舌', '慢热']
const json = { 'Content-Type': 'application/json' }

function message(reason: unknown, fallback: string) {
  return reason instanceof Error && reason.message ? reason.message : fallback
}

/** 预览里的角色能不能戳：Mowa Bot 和 Live2D 角色能。 */
const pokeable = (name: string) => characterKind(name) === 'mowa' || characterKind(name) === 'live2d'

/**
 * 角色设置：形象、名字、怎么称呼用户、性格、声音，以及给想细调的人用的完整提示词。
 * 关系固定是朋友，平台的内容安全守则不在这里，也改不掉。
 */
export default function RoleEditor({ agentId, agentName, fresh = false, onClose, onSaved, onDeleted, onHistoryCleared }: RoleEditorProps) {
  const [role, setRole] = useState<RoleSettings | null>(null)
  const [voices, setVoices] = useState<WebVoice[]>([])
  // 预设角色有为它画的形象，这里只展示，不能换成通用的小动物。空白角色才挑动物和颜色。
  const preset = hasPresetLook(agentName)
  const [look, setLook] = useState<CustomLook>(() => currentLook(agentName))
  const [lookChanged, setLookChanged] = useState(false)
  // 有几套装扮的预设角色（例如年糕）可以换装扮。
  const outfits = preset ? roleOutfits(agentName) : []
  const [outfit, setOutfitChoice] = useState(() => currentOutfit(agentName))
  // 从 Mowa Bot 切回手绘时记住刚才挑的，再切回来还是它。
  const lastMowa = useRef<Pick<CustomLook, 'mowa' | 'mowaStyle' | 'mowaVariant' | 'mowaFace'> | null>(null)
  // 预览下面点了哪个表情，正在演的那个。
  const [demoState, setDemoState] = useState<CharacterState | null>(null)
  const demoTimer = useRef(0)
  // 点预览里的 Mowa Bot 角色时戳它一下。
  const previewPoke = useRef<(() => void) | null>(null)
  useEffect(() => () => window.clearTimeout(demoTimer.current), [])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [confirming, setConfirming] = useState(false)
  const [clearing, setClearing] = useState(false)
  const [cleared, setCleared] = useState('')
  // 它记得的关于用户的事。这里的增删立刻生效，不用点保存。
  const [memories, setMemories] = useState<Memory[] | null>(null)
  const [note, setNote] = useState('')
  const [memoryBusy, setMemoryBusy] = useState(false)
  const memoryPath = `/memories?agentId=${encodeURIComponent(agentId)}`

  useEffect(() => {
    let active = true
    managerJson<RoleSettings>(`/roles/${encodeURIComponent(agentId)}`)
      .then((loaded) => {
        if (!active) return
        setRole(loaded)
        if (loaded.ttsModelId) {
          managerJson<WebVoice[]>(`/voices?ttsModelId=${encodeURIComponent(loaded.ttsModelId)}`)
            .then((items) => { if (active) setVoices(Array.isArray(items) ? items : []) })
            .catch(() => undefined)
        }
      })
      .catch((reason: unknown) => { if (active) setError(message(reason, '角色加载失败')) })
    managerJson<Memory[]>(`/memories?agentId=${encodeURIComponent(agentId)}`)
      .then((items) => { if (active) setMemories(Array.isArray(items) ? items : []) })
      .catch(() => { if (active) setMemories([]) })
    return () => { active = false }
  }, [agentId])

  async function reloadMemories() {
    const items = await managerJson<Memory[]>(memoryPath)
    setMemories(Array.isArray(items) ? items : [])
  }

  async function addMemory() {
    const content = note.trim()
    if (!content) return
    setMemoryBusy(true); setError('')
    try {
      await managerJson('/memories', { method: 'POST', headers: json, body: JSON.stringify({ agentId, content }) })
      setNote('')
      await reloadMemories()
    } catch (reason) { setError(message(reason, '没有记下来，请重试')) } finally { setMemoryBusy(false) }
  }

  async function forget(id?: string) {
    setMemoryBusy(true); setError('')
    try {
      await managerJson(id ? `${memoryPath}&id=${encodeURIComponent(id)}` : memoryPath, { method: 'DELETE' })
      await reloadMemories()
    } catch (reason) { setError(message(reason, '删除失败，请重试')) } finally { setMemoryBusy(false) }
  }

  // 预览用的形象登记在一个专门的名字下面，不影响页面上正在用的那个。
  setCustomLook(PREVIEW_NAME, look)
  setPreviewOutfit(outfits.length ? agentName : null, outfit)
  useEffect(() => () => { setCustomLook(PREVIEW_NAME, null); setPreviewOutfit(null) }, [])

  function close() {
    if (!busy) onClose()
  }

  /** 换成手绘玩偶：记下刚才挑的 Mowa Bot，去掉它。 */
  function chooseDrawn() {
    if (!look.mowa) return
    const { mowa, mowaStyle, mowaVariant, mowaFace, ...drawn } = look
    lastMowa.current = { mowa, mowaStyle, mowaVariant, mowaFace }
    setLook(drawn); setLookChanged(true)
  }

  /** 换成 Mowa Bot：回到上次挑的；第一次挑时先选和现在同一种动物的角色。 */
  function chooseMowa() {
    if (look.mowa) return
    setLook({ ...look, ...(lastMowa.current ?? { mowa: DEFAULT_CAST[look.kind] }) }); setLookChanged(true)
  }

  /** 预览演一下这个表情，过一会儿回到平常。 */
  function playDemo(state: CharacterState) {
    window.clearTimeout(demoTimer.current)
    setDemoState(state)
    demoTimer.current = window.setTimeout(() => setDemoState(null), MOWA_DEMO_MS)
  }

  // 表情是一次性的动作：点一下演一遍，过一会儿自己回到平常，不是一个选中的状态。
  const demoRow = <div className="demo-row" role="group" aria-label="看看表情">
    {MOWA_DEMO_STATES.map((item) => <button
      key={item.key} type="button" className="demo-button" onClick={() => playDemo(item.key)}
    >{item.label}</button>)}
  </div>

  // 声音选择页开着时，Esc 只关掉它，回到角色设置。
  const [pickingVoice, setPickingVoice] = useState(false)
  // 形象选择页开着时也一样。
  const [pickingLook, setPickingLook] = useState(false)

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      if (pickingVoice) { stopPreview(); setPickingVoice(false) }
      else if (pickingLook) setPickingLook(false)
      else close()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  // 试听：服务端用选中的音色读一句固定的话。再点一下停止；换音色或关掉设置时也停。
  const [preview, setPreview] = useState<{ id: string; state: 'loading' | 'playing' } | null>(null)
  const previewAudio = useRef<HTMLAudioElement | null>(null)
  const previewUrl = useRef<string | null>(null)
  const previewRun = useRef(0)
  // 试听失败的提示放在声音这一栏下面：页面顶部的提示这时往往已经滚出屏幕。
  const [previewError, setPreviewError] = useState('')

  function releasePreview() {
    previewRun.current += 1
    previewAudio.current?.pause()
    previewAudio.current = null
    if (previewUrl.current) URL.revokeObjectURL(previewUrl.current)
    previewUrl.current = null
  }

  function stopPreview() {
    releasePreview()
    setPreview(null)
    setPreviewError('')
  }

  useEffect(() => releasePreview, [])

  /** 试听一个音色，默认是选中的那个；声音选择页里每个音色都能试听。 */
  async function togglePreview(id = role?.ttsVoiceId) {
    const playing = preview?.id
    stopPreview()
    if (!id || playing === id) return
    const run = previewRun.current
    setPreview({ id, state: 'loading' })
    try {
      // 有官方试听音频的音色直接播放官方的，不用自己合成；没有的才让服务端读一句。
      let url = demoUrl(voices.find((voice) => voice.id === id)?.voiceDemo)
      if (!url) {
        const response = await fetch(`/api/voices/${encodeURIComponent(id)}/preview`)
        if (!response.ok) {
          const payload = await response.json().catch(() => ({})) as { error?: string }
          throw new Error(payload.error || '试听失败，请稍后再试')
        }
        const blob = await response.blob()
        if (run !== previewRun.current) return
        url = URL.createObjectURL(blob)
        previewUrl.current = url
      }
      const audio = new Audio(url)
      previewAudio.current = audio
      audio.onended = () => { if (run === previewRun.current) stopPreview() }
      setPreview({ id, state: 'playing' })
      await audio.play()
    } catch (reason) {
      if (run !== previewRun.current) return
      stopPreview()
      setPreviewError(message(reason, '试听失败，请稍后再试'))
    }
  }

  // 声音可能来自几个语音合成模型，选中的音色属于哪个模型，保存时角色就用哪个模型。
  // 豆包有几百个音色，在单独的声音选择页里按场景、性别、年龄、语言挑（见 VoicePicker）。
  const currentVoice = voices.find((voice) => voice.id === role?.ttsVoiceId)
  const currentInfo = currentVoice ? voiceInfo(currentVoice) : null
  const voiceModel = currentVoice?.ttsModelId || null

  function change(patch: Partial<RoleSettings>) {
    setRole((current) => current ? { ...current, ...patch } : current)
  }

  function toggleTrait(trait: string) {
    if (!role) return
    const parts = role.personality.split(/[、，,\s]+/).filter(Boolean)
    const next = parts.includes(trait) ? parts.filter((item) => item !== trait) : [...parts, trait]
    change({ personality: next.join('、') })
  }

  async function save() {
    if (!role) return
    const name = role.name.trim()
    if (!name) { setError('给它起个名字吧'); return }
    setBusy(true); setError('')
    try {
      await managerJson(`/roles/${encodeURIComponent(agentId)}`, {
        method: 'PUT', headers: json,
        body: JSON.stringify({
          name, userAddress: role.userAddress, personality: role.personality,
          systemPrompt: role.systemPrompt, ttsVoiceId: role.ttsVoiceId,
          // 选的是另一个语音合成模型的音色时，模型一起换。
          ...(voiceModel && voiceModel !== role.ttsModelId ? { ttsModelId: voiceModel } : {}),
        }),
      })
      if (lookChanged && !preset) saveLook(agentId, look)
      if (outfits.length && outfit) { setOutfit(agentName, outfit); setOutfit(name, outfit); saveOutfit(agentId, outfit) }
      onSaved()
    } catch (reason) { setError(message(reason, '保存失败，请重试')) } finally { setBusy(false) }
  }

  async function restorePrompt() {
    setBusy(true); setError('')
    try {
      await managerJson(`/roles/${encodeURIComponent(agentId)}/restore-prompt`, { method: 'POST' })
      const loaded = await managerJson<RoleSettings>(`/roles/${encodeURIComponent(agentId)}`)
      change({ systemPrompt: loaded.systemPrompt })
    } catch (reason) { setError(message(reason, '恢复失败，请重试')) } finally { setBusy(false) }
  }

  async function clearHistory() {
    setBusy(true); setError(''); setCleared('')
    try {
      await managerJson(`/history?agentId=${encodeURIComponent(agentId)}`, { method: 'DELETE' })
      setClearing(false)
      setCleared(`和${role?.name || '它'}的对话记录已经清空`)
      onHistoryCleared?.()
      await reloadMemories().catch(() => undefined)
    } catch (reason) { setError(message(reason, '清空失败，请重试')) } finally { setBusy(false) }
  }

  async function remove() {
    setBusy(true); setError('')
    try {
      await managerJson(`/roles/${encodeURIComponent(agentId)}`, { method: 'DELETE' })
      saveLook(agentId, null)
      saveOutfit(agentId, null)
      onDeleted()
    } catch (reason) { setError(message(reason, '删除失败，请重试')); setBusy(false) }
  }

  // 形象：不管是哪种角色，都是上面一个预览、下面一行现在的形象，点开是形象选择页。
  // 有几套装扮的预设角色换装扮；自己做的角色挑手绘玩偶或 Mowa Bot；其余预设角色用为它画的形象，不能换。
  const lookChangeable = !preset || outfits.length > 0
  const stageName = lookChangeable ? PREVIEW_NAME : agentName
  const lookPreview = <div className={`look-preview ${characterStyle() !== 'none' && pokeable(stageName) ? 'pokeable' : ''}`} onClick={() => previewPoke.current?.()}>
    {characterStyle() === 'none' ? <RoleFigure name={stageName} size={220} />
      : <RoleCharacter key={outfits.length ? outfit : undefined} name={stageName} mood="idle" demoState={demoState} pokeRef={previewPoke} />}
  </div>
  // 只有 Mowa Bot 会演表情。形象选择页上预览只放角色本身，表情按钮放在 Mowa Bot 那一栏里，
  // 这样切换手绘玩偶和 Mowa Bot 时预览一样高。
  const expressions = characterStyle() !== 'none' && characterKind(stageName) === 'mowa' ? demoRow : null
  const lookStage = <div className="look-stage">{lookPreview}{expressions}</div>
  const chosenOutfit = outfits.find((item) => item.key === outfit)
  const lookTitle = chosenOutfit ? chosenOutfit.label
    : preset ? '专属形象'
    : look.mowa ? 'Mowa Bot'
    : LOOK_KINDS.find((item) => item.key === look.kind)?.label ?? '形象'
  const lookThumb = <span className="look-thumb"><RoleFigure name={stageName} size={44} /></span>
  const lookText = chosenOutfit ? <><strong>{chosenOutfit.label}</strong><span>装扮 · 共 {outfits.length} 套</span></>
    : preset ? <><strong>专属形象</strong><span>为{agentName}画的形象，不能更换</span></>
    : look.mowa && characterStyle() !== 'live2d' ? <MowaLookSummary look={look} />
    : <><strong>{lookTitle}</strong><span>手绘玩偶 · {LOOK_COLORS.find((item) => item.key === look.color)?.label ?? ''}</span></>

  const traits = role ? role.personality.split(/[、，,\s]+/).filter(Boolean) : []
  const aura = preset ? roleLook(outfits.length ? PREVIEW_NAME : agentName).aura : LOOK_COLORS.find((color) => color.key === look.color)?.aura
  return <main
    className="editor-page" aria-label="角色设置"
    style={aura ? { '--aura-1': aura[0], '--aura-2': aura[1], '--aura-3': aura[2] } as React.CSSProperties : undefined}
  >
    <div className="ambient" aria-hidden="true" />
    <header className="editor-bar">
      <button type="button" className="glass-button" onClick={close} disabled={busy} aria-label="返回"><ArrowLeft size={22} /></button>
      <h1>{fresh ? '新角色' : '角色设置'}</h1>
      <button type="button" className="button primary" onClick={() => void save()} disabled={busy || !role}>{busy ? '正在保存' : '保存'}</button>
    </header>
    <div className="editor-body">
      {error && <div className="error-banner" role="alert">{error}</div>}
      {!role && !error && <div className="loading-state">正在读取</div>}

      {role && <div className="editor-grid">
        <div className="sheet-group editor-look">
          <p className="sheet-group-title">形象</p>
          {/* 形象选择页开着时预览在那一页上，这里不再画一个。 */}
          {!pickingLook && lookStage}
          {lookChangeable
            ? <button type="button" className="voice-current look-current" onClick={() => setPickingLook(true)} aria-label={`形象：${lookTitle}，点一下换形象`}>
              {lookThumb}
              <span className="voice-current-text">{lookText}</span>
              <span className="voice-current-more">换形象<ChevronRight size={18} aria-hidden="true" /></span>
            </button>
            : <div className="voice-current look-current" aria-label={`形象：${lookTitle}`} role="group">
              {lookThumb}
              <span className="voice-current-text">{lookText}</span>
              <Lock size={16} className="look-lock" aria-hidden="true" />
            </div>}
        </div>

        <div className="editor-fields">
        <div className="sheet-group">
          <label className="field">名字
            <input type="text" value={role.name} maxLength={64} onChange={(event) => change({ name: event.target.value })} aria-label="名字" placeholder="给它起个名字" />
          </label>
          <label className="field">它怎么称呼你
            <input type="text" value={role.userAddress} maxLength={64} onChange={(event) => change({ userAddress: event.target.value })} aria-label="它怎么称呼你" placeholder="比如你的小名，可以不填" />
          </label>
          <label className="field">性格
            <textarea
              value={role.personality} maxLength={1000} rows={3} aria-label="性格"
              onChange={(event) => change({ personality: event.target.value })}
              placeholder="用自己的话写写它是个什么样的朋友，也可以点下面的词"
            />
          </label>
          <div className="trait-row">
            {TRAITS.map((trait) => <button
              key={trait} type="button" className="trait" aria-pressed={traits.includes(trait)} onClick={() => toggleTrait(trait)}
            >{trait}</button>)}
          </div>
          <div className="field">声音
            <div className="field-row voice-row">
            <button
              type="button" className="voice-current" onClick={() => setPickingVoice(true)} disabled={!voices.length}
              aria-label={`声音：${currentInfo?.label || (role.ttsVoiceId ? '现在的声音' : '默认的声音')}，点一下换声音`}
            >
              <span className="voice-current-text">
                <strong>{currentInfo?.label || (role.ttsVoiceId ? '现在的声音' : '默认的声音')}</strong>
                {currentInfo && <span>{[currentInfo.scene, voiceTags(currentInfo)].filter(Boolean).join(' · ')}</span>}
              </span>
              <span className="voice-current-more">{voices.length ? `${voices.length} 个可选` : ''}<ChevronRight size={18} aria-hidden="true" /></span>
            </button>
            <button type="button" className="button" onClick={() => void togglePreview()} disabled={!role.ttsVoiceId}
              aria-label={preview && preview.id === role.ttsVoiceId ? '停止试听' : '试听这个声音'}>
              {preview?.id === role.ttsVoiceId && preview?.state === 'loading' ? <Loader2 size={18} className="spin" aria-hidden="true" />
                : preview?.id === role.ttsVoiceId ? <Square size={16} aria-hidden="true" /> : <Volume2 size={18} aria-hidden="true" />}
              {preview?.id === role.ttsVoiceId && preview?.state === 'loading' ? '合成中' : preview?.id === role.ttsVoiceId ? '停止' : '试听'}
            </button>
            </div>
            {currentInfo?.description && <p className="field-note">{currentInfo.description}</p>}
            {previewError && <p className="field-note field-alert" role="alert">{previewError}</p>}
          </div>
        </div>

        <div className="sheet-group">
          <p className="sheet-group-title">它记得的事</p>
          <p className="field-note">聊天时它会自己记下关于你的事，下次聊还记得。你可以删掉不想让它记的，也可以自己写一条。这里的改动立刻生效，下次开始对话时用上。</p>
          {memories === null && <div className="loading-state">正在读取</div>}
          {memories !== null && memories.length === 0 && <p className="memory-empty">它还没有记下什么。多聊几句就会有了。</p>}
          {memories !== null && memories.length > 0 && <ul className="memory-list" aria-label="它记得的事">
            {memories.map((item) => <li key={item.id}>
              <span>{item.content}</span>
              <button type="button" className="tool-button" aria-label={`忘掉：${item.content}`} disabled={memoryBusy} onClick={() => void forget(item.id)}><X size={16} /></button>
            </li>)}
          </ul>}
          <div className="memory-add">
            <input
              type="text" value={note} maxLength={200} aria-label="写一件事让它记住" placeholder="写一件事让它记住，比如“我不吃香菜”"
              onChange={(event) => setNote(event.target.value)}
              onKeyDown={(event) => { if (event.key === 'Enter' && !event.nativeEvent.isComposing) { event.preventDefault(); void addMemory() } }}
            />
            <button type="button" className="button" onClick={() => void addMemory()} disabled={memoryBusy || !note.trim()}>记住</button>
          </div>
          {memories !== null && memories.length > 1 && <button type="button" className="link-button" onClick={() => void forget()} disabled={memoryBusy}>全部忘掉</button>}
        </div>

        <details className="advanced">
          <summary>高级：完整的提示词</summary>
          <p className="field-note">这段文字决定它怎么说话、守哪些规矩，性格那一栏是它的补充。写乱了可以恢复。平台的内容安全守则不在这里，也不会因为改了这里而失效。</p>
          <textarea
            value={role.systemPrompt} maxLength={16000} rows={9} aria-label="完整的提示词"
            onChange={(event) => change({ systemPrompt: event.target.value })}
          />
          {role.fromPreset && <button type="button" className="link-button" onClick={() => void restorePrompt()} disabled={busy}>恢复成预设的提示词</button>}
        </details>

        {cleared && <div className="notice-banner" role="status">{cleared}</div>}
        {/* 两个危险操作放在一张实底的卡片里，和个人中心的退出登录、注销一样，不直接压在页面底部的光晕上。 */}
        {!clearing && !confirming && <div className="sheet-list">
          <button type="button" className="sheet-item danger" onClick={() => setClearing(true)} disabled={busy}>清空和{role.name || '它'}的对话记录</button>
          <button type="button" className="sheet-item danger" onClick={() => setConfirming(true)} disabled={busy}>删除这个角色</button>
        </div>}
        {clearing
          && <div className="sheet-body danger-zone">
            <p>确定清空和{role.name || '它'}的全部对话记录吗？清空后无法恢复，{role.name || '它'}也不会再记得之前聊过的内容。</p>
            <div className="choice-row">
              <button type="button" className="button danger" onClick={() => void clearHistory()} disabled={busy}>确认清空</button>
              <button type="button" className="button" onClick={() => setClearing(false)} disabled={busy}>再想想</button>
            </div>
          </div>}
        {confirming
          && <div className="sheet-body danger-zone">
            <p>确定删除{role.name || '这个角色'}吗？和它的对话记录会一起删掉，无法恢复。</p>
            <div className="choice-row">
              <button type="button" className="button danger" onClick={() => void remove()} disabled={busy}>确认删除</button>
              <button type="button" className="button" onClick={() => setConfirming(false)} disabled={busy}>再想想</button>
            </div>
          </div>}
        </div>
      </div>}
    </div>
    {pickingLook && role && <LookPicker
      stage={lookPreview} expressions={expressions} outfits={outfits} outfit={outfit} onOutfit={setOutfitChoice}
      look={look} onLook={(next) => { setLook(next); setLookChanged(true) }} onDrawn={chooseDrawn} onMowa={chooseMowa}
      style={characterStyle()} onClose={() => setPickingLook(false)}
    />}
    {pickingVoice && role && <VoicePicker
      voices={voices} selectedId={role.ttsVoiceId} preview={preview} previewError={previewError}
      onPreview={(id) => void togglePreview(id)}
      onSelect={(id) => { stopPreview(); change({ ttsVoiceId: id }); setPickingVoice(false) }}
      onClose={() => { stopPreview(); setPickingVoice(false) }}
    />}
  </main>
}
