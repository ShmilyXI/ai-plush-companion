'use client'

import { useEffect, useState } from 'react'

interface AccountPanelProps {
  roleName: string
  agentId: string
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

/** 用户管理自己数据的地方：清空和某个角色的对话记录、注销账号、退出登录。 */
export default function AccountPanel({ roleName, agentId, onClose, onHistoryCleared, onSignOut, onAccountDeleted }: AccountPanelProps) {
  const [view, setView] = useState<'menu' | 'clear' | 'delete'>('menu')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [code, setCode] = useState('')
  const [codeSent, setCodeSent] = useState(false)

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape' && !busy) onClose() }
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

  return <div className="sheet-backdrop" onClick={() => { if (!busy) onClose() }}>
    <section className="sheet" role="dialog" aria-modal="true" aria-label="账号与数据" onClick={(event) => event.stopPropagation()}>
      <header className="sheet-header">
        <h2>{view === 'menu' ? '账号与数据' : view === 'clear' ? '清空对话记录' : '注销账号'}</h2>
        <button type="button" className="link-button" onClick={() => view === 'menu' ? onClose() : open('menu')} disabled={busy}>
          {view === 'menu' ? '关闭' : '返回'}
        </button>
      </header>
      {notice && <div className="notice-banner" role="status">{notice}</div>}
      {error && <div className="error-banner" role="alert">{error}</div>}

      {view === 'menu' && <div className="sheet-list">
        <button type="button" className="sheet-item" onClick={() => open('clear')} disabled={!agentId}>
          <span>清空和{roleName}的对话记录</span><small>清空后{roleName}不再记得之前聊过什么</small>
        </button>
        <a className="sheet-item" href="/privacy" target="_blank" rel="noreferrer"><span>隐私政策</span><small>我们保存什么、怎么用</small></a>
        <a className="sheet-item" href="/terms" target="_blank" rel="noreferrer"><span>用户协议</span></a>
        <button type="button" className="sheet-item" onClick={onSignOut}><span>退出登录</span></button>
        <button type="button" className="sheet-item danger" onClick={() => open('delete')}>
          <span>注销账号</span><small>删除账号和所有数据，不可恢复</small>
        </button>
      </div>}

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
    </section>
  </div>
}
