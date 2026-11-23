'use client'

import { useEffect, useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { THEMES, applyTheme, readTheme, type Theme } from '../lib/theme'
import { readProactive, saveProactive } from '../lib/companionMode'
import Avatar from './Avatar'

interface AccountPanelProps {
  roleName: string
  agentId: string
  /** 手机号登录的账号才有退出登录和注销。从管理台过来的会话由管理台管登录，这两项不显示。 */
  account?: boolean
  /** 打开当前角色的设置（名字、性格、声音这些）。 */
  onEditRole?: () => void
  onClose: () => void
  /** 对话记录已经清空，页面上显示的消息也要跟着清掉。 */
  onHistoryCleared: () => void
  onSignOut: () => void
  /** 账号已经注销，回到登录页。 */
  onAccountDeleted: () => void
}

async function call(path: string, method: string, body?: Record<string, unknown>) {
  const response = await fetch(path, {
    method, cache: 'no-store',
    ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}),
  })
  const payload = await response.json().catch(() => ({})) as { error?: string }
  return { ok: response.ok, error: payload.error || '' }
}

/** 设置：当前角色的声音和对话记录，以及账号（协议、退出登录、注销）。 */
export default function AccountPanel({
  roleName, agentId, account = true, onEditRole,
  onClose, onHistoryCleared, onSignOut, onAccountDeleted,
}: AccountPanelProps) {
  const [view, setView] = useState<'menu' | 'clear' | 'delete'>('menu')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [code, setCode] = useState('')
  const [codeSent, setCodeSent] = useState(false)
  const [theme, setTheme] = useState<Theme>('auto')
  const [proactive, setProactive] = useState(true)
  useEffect(() => { setTheme(readTheme()); setProactive(readProactive()) }, [])

  function close() {
    if (!busy) onClose()
  }

  /** 左上角的返回：在子页面里回到设置的首页，在首页就离开设置。 */
  function back() {
    if (busy) return
    if (view === 'menu') onClose()
    else open('menu')
  }

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape' && !busy) close() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [busy, onClose])

  function open(next: 'menu' | 'clear' | 'delete') {
    setView(next); setError(''); setNotice(''); setCode(''); setCodeSent(false)
  }

  async function clearHistory() {
    setBusy(true); setError('')
    try {
      const result = await call(`/api/history?agentId=${encodeURIComponent(agentId)}`, 'DELETE')
      if (!result.ok) { setError(result.error || '清空失败，请重试'); return }
      onHistoryCleared()
      setView('menu'); setNotice(`和${roleName}的对话记录已经清空`)
    } catch { setError('网络异常，请稍后重试') } finally { setBusy(false) }
  }

  async function sendCode() {
    setBusy(true); setError('')
    try {
      const result = await call('/api/account/sms-code', 'POST')
      if (!result.ok) { setError(result.error || '验证码发送失败'); return }
      setCodeSent(true)
    } catch { setError('网络异常，请稍后重试') } finally { setBusy(false) }
  }

  async function deleteAccount() {
    if (!/^\d{4,8}$/.test(code.trim())) { setError('请输入短信里的验证码'); return }
    setBusy(true); setError('')
    try {
      const result = await call('/api/account/delete', 'POST', { code: code.trim() })
      if (!result.ok) { setError(result.error || '注销失败，请重试'); return }
      onAccountDeleted()
    } catch { setError('网络异常，请稍后重试') } finally { setBusy(false) }
  }

  return <main className="editor-page settings-page" aria-label="设置">
    <div className="ambient" aria-hidden="true" />
    <header className="editor-bar">
      <button type="button" className="glass-button" onClick={back} disabled={busy} aria-label="返回"><ArrowLeft size={22} /></button>
      <h1>{view === 'menu' ? '设置' : view === 'clear' ? '清空对话记录' : '注销账号'}</h1>
    </header>
    <div className="editor-body"><div className="settings-column">
      {notice && <div className="notice-banner" role="status">{notice}</div>}
      {error && <div className="error-banner" role="alert">{error}</div>}

      {view === 'menu' && <>
        <div className="sheet-group">
          <p className="sheet-group-title">外观</p>
          <div className="choice-row" role="radiogroup" aria-label="主题">
            {THEMES.map((item) => <button
              key={item.key} type="button" role="radio" aria-checked={theme === item.key} className="choice"
              onClick={() => { setTheme(item.key); applyTheme(item.key) }}
            >{item.label}</button>)}
          </div>
        </div>
        <div className="sheet-group">
          <p className="sheet-group-title">通话</p>
          <div className="sheet-list">
            <button
              type="button" role="switch" aria-checked={proactive} className="sheet-item sheet-switch"
              onClick={() => { const next = !proactive; setProactive(next); saveProactive(next) }}
            >
              <span>主动找话题</span>
              <small>通话时你一阵子没说话，它会自己找个话题。没有合适的话题时它会安静陪着。</small>
              <i aria-hidden="true" />
            </button>
          </div>
        </div>
        <div className="sheet-group">
          <p className="sheet-group-title"><Avatar name={roleName} size={24} />{roleName}</p>
          <div className="sheet-list">
            {onEditRole && <button type="button" className="sheet-item" onClick={onEditRole}>
              <span>角色设置</span><small>名字、性格、声音和形象</small>
            </button>}
            <button type="button" className="sheet-item" onClick={() => open('clear')} disabled={!agentId}>
              <span>清空和{roleName}的对话记录</span><small>清空后{roleName}不再记得之前聊过什么</small>
            </button>
          </div>
        </div>
        <div className="sheet-group">
          <p className="sheet-group-title">{account ? '账号' : '关于'}</p>
          <div className="sheet-list">
            <a className="sheet-item" href="/privacy" target="_blank" rel="noreferrer"><span>隐私政策</span><small>我们保存什么、怎么用</small></a>
            <a className="sheet-item" href="/terms" target="_blank" rel="noreferrer"><span>用户协议</span></a>
            {account && <button type="button" className="sheet-item" onClick={onSignOut}><span>退出登录</span></button>}
            {account && <button type="button" className="sheet-item danger" onClick={() => open('delete')}>
              <span>注销账号</span><small>删除账号和所有数据，不可恢复</small>
            </button>}
          </div>
        </div>
      </>}

      {view === 'clear' && <div className="sheet-body">
        <p>确定清空和{roleName}的全部对话记录吗？清空后无法恢复，{roleName}也不会再记得之前聊过的内容。</p>
        <button type="button" className="button danger" onClick={() => void clearHistory()} disabled={busy}>{busy ? '正在清空' : '确认清空'}</button>
      </div>}

      {view === 'delete' && <div className="sheet-body">
        <p>注销后，你的账号、角色和全部对话记录都会被删除，无法恢复。只含数量、不含内容的统计记录会保留，但不再对应到你。</p>
        {!codeSent
          ? <button type="button" className="button" onClick={() => void sendCode()} disabled={busy}>{busy ? '发送中' : '给我的手机发验证码'}</button>
          : <>
            <label className="field">短信验证码
              <input
                type="text" inputMode="numeric" autoComplete="one-time-code" placeholder="6 位验证码" maxLength={8}
                value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))} aria-label="注销验证码"
              />
            </label>
            <button type="button" className="button danger" onClick={() => void deleteAccount()} disabled={busy}>{busy ? '正在注销' : '确认注销账号'}</button>
          </>}
      </div>}
    </div></div>
  </main>
}
