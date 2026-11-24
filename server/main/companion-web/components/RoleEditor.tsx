'use client'

import { useEffect, useState } from 'react'
import { ArrowLeft, X } from 'lucide-react'
import { managerJson, type WebVoice } from '../lib/manager'
import { LIVE2D_MODELS, LOOK_COLORS, LOOK_KINDS, PREVIEW_NAME, currentLook, hasPresetLook, roleLook, saveLook, setCustomLook, type CustomLook } from '../lib/roles'
import RoleCharacter, { characterStyle } from './RoleCharacter'
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
}

/** 点一下就加进性格描述里的词。 */
const TRAITS = ['温柔', '直接', '话多', '爱开玩笑', '冷静', '好奇心重', '有点毒舌', '慢热']
const json = { 'Content-Type': 'application/json' }

function message(reason: unknown, fallback: string) {
  return reason instanceof Error && reason.message ? reason.message : fallback
}

/**
 * 角色设置：形象、名字、怎么称呼用户、性格、声音，以及给想细调的人用的完整提示词。
 * 关系固定是朋友，平台的内容安全守则不在这里，也改不掉。
 */
export default function RoleEditor({ agentId, agentName, fresh = false, onClose, onSaved, onDeleted }: RoleEditorProps) {
  const [role, setRole] = useState<RoleSettings | null>(null)
  const [voices, setVoices] = useState<WebVoice[]>([])
  // 预设角色有为它画的形象，这里只展示，不能换成通用的小动物。空白角色才挑动物和颜色。
  const preset = hasPresetLook(agentName)
  const [look, setLook] = useState<CustomLook>(() => currentLook(agentName))
  const [lookChanged, setLookChanged] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [confirming, setConfirming] = useState(false)
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
  useEffect(() => () => setCustomLook(PREVIEW_NAME, null), [])

  function close() {
    if (!busy) onClose()
  }

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') close() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

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
        }),
      })
      if (lookChanged && !preset) saveLook(agentId, look)
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

  async function remove() {
    setBusy(true); setError('')
    try {
      await managerJson(`/roles/${encodeURIComponent(agentId)}`, { method: 'DELETE' })
      saveLook(agentId, null)
      onDeleted()
    } catch (reason) { setError(message(reason, '删除失败，请重试')); setBusy(false) }
  }

  const traits = role ? role.personality.split(/[、，,\s]+/).filter(Boolean) : []
  const aura = preset ? roleLook(agentName).aura : LOOK_COLORS.find((color) => color.key === look.color)?.aura
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
          {/* 会动的角色没启用时，预览是一张静态的图。 */}
          {preset ? <div className="look-preview">
            {characterStyle() === 'none' ? <RoleFigure name={agentName} size={220} /> : <RoleCharacter name={agentName} mood="idle" />}
          </div> : <>
            <div className="look-preview">
              {characterStyle() === 'none' ? <RoleFigure name={PREVIEW_NAME} size={220} /> : <RoleCharacter name={PREVIEW_NAME} mood="idle" />}
            </div>
            {characterStyle() === 'live2d' && <div className="choice-row" role="radiogroup" aria-label="模型">
              {LIVE2D_MODELS.map((item) => <button
                key={item.key} type="button" role="radio" aria-checked={(look.model ?? LIVE2D_MODELS[0].key) === item.key} className="choice"
                onClick={() => { setLook({ ...look, model: item.key }); setLookChanged(true) }}
              >{item.label}</button>)}
            </div>}
            {characterStyle() !== 'live2d' && <div className="choice-row" role="radiogroup" aria-label="动物">
              {LOOK_KINDS.map((kind) => <button
                key={kind.key} type="button" role="radio" aria-checked={look.kind === kind.key} className="choice"
                onClick={() => { setLook({ ...look, kind: kind.key }); setLookChanged(true) }}
              >{kind.label}</button>)}
            </div>}
            <div className="swatch-row" role="radiogroup" aria-label="颜色">
              {LOOK_COLORS.map((color) => <button
                key={color.key} type="button" role="radio" aria-checked={look.color === color.key} aria-label={color.label} title={color.label}
                className="swatch" style={{ background: color.plush.fur }}
                onClick={() => { setLook({ ...look, color: color.key }); setLookChanged(true) }}
              />)}
            </div>
            <p className="field-note">{characterStyle() === '3d' ? '' : '颜色是和它通话时页面的光。'}形象记在这台设备上，换设备会回到默认的样子。</p>
          </>}
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
          <label className="field">声音
            <select value={role.ttsVoiceId} onChange={(event) => change({ ttsVoiceId: event.target.value })} aria-label="声音">
              {!voices.some((voice) => voice.id === role.ttsVoiceId) && <option value={role.ttsVoiceId}>{role.ttsVoiceId ? '现在的声音' : '默认的声音'}</option>}
              {voices.map((voice) => <option key={voice.id} value={voice.id}>{voice.name}</option>)}
            </select>
          </label>
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

        {confirming
          ? <div className="sheet-body danger-zone">
            <p>确定删除{role.name || '这个角色'}吗？和它的对话记录会一起删掉，无法恢复。</p>
            <div className="choice-row">
              <button type="button" className="button danger" onClick={() => void remove()} disabled={busy}>确认删除</button>
              <button type="button" className="button" onClick={() => setConfirming(false)} disabled={busy}>再想想</button>
            </div>
          </div>
          : <button type="button" className="link-button danger-link" onClick={() => setConfirming(true)} disabled={busy}>删除这个角色</button>}
        </div>
      </div>}
    </div>
  </main>
}
