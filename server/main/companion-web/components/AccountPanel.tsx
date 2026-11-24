'use client'

import { useEffect, useState } from 'react'
import { ArrowLeft, Eye, EyeOff, UserRound } from 'lucide-react'
import { THEMES, applyTheme, readTheme, type Theme } from '../lib/theme'
import { readProactive, saveProactive } from '../lib/companionMode'
import { PASSWORD_RULE, encryptPassword, passwordProblem } from '../lib/password'
import { daysTogether, loadProfile, planLine, type ProfileSummary } from '../lib/profile'

interface AccountPanelProps {
  /** 用户现在有几个角色，和套餐上限一起显示。 */
  roleCount: number
  /** 手机号登录的账号才有退出登录和注销。从管理台过来的会话由管理台管登录，这两项不显示。 */
  account?: boolean
  onClose: () => void
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

/**
 * 个人中心：自己的账号和套餐、对整个应用的偏好、协议、退出登录和注销。
 * 某个角色的东西（名字、声音、记得的事、对话记录）都在角色设置里，不放在这里。
 */
export default function AccountPanel({ roleCount, account = true, onClose, onSignOut, onAccountDeleted }: AccountPanelProps) {
  const [view, setView] = useState<'menu' | 'delete' | 'password'>('menu')
  const [newPassword, setNewPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [profile, setProfile] = useState<ProfileSummary | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [code, setCode] = useState('')
  const [codeSent, setCodeSent] = useState(false)
  const [theme, setTheme] = useState<Theme>('auto')
  const [proactive, setProactive] = useState(true)
  useEffect(() => { setTheme(readTheme()); setProactive(readProactive()) }, [])
  useEffect(() => {
    let active = true
    void loadProfile().then((loaded) => { if (active) setProfile(loaded) })
    return () => { active = false }
  }, [])

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

  function open(next: 'menu' | 'delete' | 'password') {
    setView(next); setError(''); setNotice(''); setCode(''); setCodeSent(false); setNewPassword(''); setShowPassword(false)
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

  async function savePassword() {
    if (!/^\d{4,8}$/.test(code.trim())) { setError('请输入短信里的验证码'); return }
    const problem = passwordProblem(newPassword)
    if (problem) { setError(problem); return }
    setBusy(true); setError('')
    try {
      const result = await call('/api/account/password', 'POST', { code: code.trim(), password: await encryptPassword(newPassword) })
      if (!result.ok) { setError(result.error || '密码设置失败，请重试'); return }
      const hadPassword = Boolean(profile?.hasPassword)
      open('menu')
      setNotice(hadPassword ? '密码已修改，下次用新密码登录' : '密码已设置，下次可以用手机号和密码登录')
      setProfile((current) => current ? { ...current, hasPassword: true } : current)
    } catch (reason) {
      setError(reason instanceof Error && reason.message ? reason.message : '网络异常，请稍后重试')
    } finally { setBusy(false) }
  }

  const together = daysTogether(profile?.since ?? null)
  const passwordTitle = profile?.hasPassword ? '修改密码' : '设置密码'
  return <main className="editor-page settings-page" aria-label="个人中心">
    <div className="ambient" aria-hidden="true" />
    <header className="editor-bar">
      <button type="button" className="glass-button" onClick={back} disabled={busy} aria-label="返回"><ArrowLeft size={22} /></button>
      <h1>{view === 'menu' ? '个人中心' : view === 'password' ? passwordTitle : '注销账号'}</h1>
    </header>
    <div className="editor-body"><div className="settings-column">
      {notice && <div className="notice-banner" role="status">{notice}</div>}
      {error && <div className="error-banner" role="alert">{error}</div>}

      {view === 'menu' && <>
        <div className="sheet-group profile-card" aria-label="我的账号">
          <span className="profile-avatar" aria-hidden="true"><UserRound size={26} /></span>
          <span className="profile-text">
            <span className="profile-name">{profile?.label ?? '我的账号'}</span>
            <span className="profile-meta">{[planLine(profile, roleCount), together ? `已陪伴 ${together} 天` : ''].filter(Boolean).join(' · ')}</span>
          </span>
        </div>
        {account && <div className="sheet-group">
          <p className="sheet-group-title">账号安全</p>
          <div className="sheet-list">
            <button type="button" className="sheet-item" onClick={() => open('password')}>
              <span>{passwordTitle}</span>
              <small>{profile?.hasPassword ? '用发到手机上的验证码确认后换一个新密码' : '设置后可以用手机号和密码登录'}</small>
            </button>
          </div>
        </div>}
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
          <p className="sheet-group-title">关于</p>
          <div className="sheet-list">
            <a className="sheet-item" href="/privacy" target="_blank" rel="noreferrer"><span>隐私政策</span><small>我们保存什么、怎么用</small></a>
            <a className="sheet-item" href="/terms" target="_blank" rel="noreferrer"><span>用户协议</span></a>
          </div>
        </div>
        {account && <div className="sheet-group">
          <div className="sheet-list">
            <button type="button" className="sheet-item" onClick={onSignOut}><span>退出登录</span></button>
            <button type="button" className="sheet-item danger" onClick={() => open('delete')}>
              <span>注销账号</span><small>删除账号和所有数据，不可恢复</small>
            </button>
          </div>
        </div>}
      </>}

      {view === 'password' && <div className="sheet-body">
        <p>验证码会发到{profile?.label && profile.label !== '我的账号' ? ` ${profile.label} ` : '你的手机号'}上，用它确认是你本人。忘了旧密码也可以这样改。</p>
        {!codeSent
          ? <button type="button" className="button" onClick={() => void sendCode()} disabled={busy}>{busy ? '发送中' : '给我的手机发验证码'}</button>
          : <>
            <label className="field">短信验证码
              <input
                type="text" inputMode="numeric" autoComplete="one-time-code" placeholder="6 位验证码" maxLength={8}
                value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))} aria-label="密码验证码"
              />
            </label>
            <label className="field">新密码
              <span className="password-box">
                <input
                  type={showPassword ? 'text' : 'password'} autoComplete="new-password" placeholder={PASSWORD_RULE} maxLength={32}
                  value={newPassword} onChange={(event) => setNewPassword(event.target.value)} aria-label="新密码"
                />
                <button type="button" className="password-eye" onClick={() => setShowPassword((value) => !value)} aria-label={showPassword ? '隐藏密码' : '显示密码'}>
                  {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </span>
            </label>
            <button type="button" className="button primary" onClick={() => void savePassword()} disabled={busy}>{busy ? '正在保存' : '保存密码'}</button>
          </>}
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
