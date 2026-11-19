'use client'

import { useEffect, useRef, useState, type FormEvent } from 'react'

/** manager-api 的业务错误码：首次登录需要邀请码、邀请码无效。 */
const INVITE_REQUIRED = 10210
const INVITE_INVALID = 10211
const RESEND_SECONDS = 60

interface ApiFailure { error?: string; code?: number }

async function post(path: string, body: Record<string, unknown>) {
  const response = await fetch(path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), cache: 'no-store',
  })
  const payload = await response.json().catch(() => ({})) as ApiFailure
  return { ok: response.ok, error: payload.error || '', code: payload.code }
}

function validPhone(value: string) {
  const digits = value.replace(/[\s-]/g, '')
  return /^(\+?86)?1[3-9]\d{9}$/.test(digits) || /^\+\d{6,15}$/.test(digits)
}

export default function LoginPanel({ onSignedIn }: { onSignedIn: () => void }) {
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [inviteNeeded, setInviteNeeded] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [sending, setSending] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const codeInput = useRef<HTMLInputElement>(null)
  const inviteInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (cooldown <= 0) return
    const timer = window.setTimeout(() => setCooldown((value) => value - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [cooldown])

  async function sendCode() {
    setError(''); setNotice('')
    if (!validPhone(phone)) { setError('请输入正确的手机号'); return }
    setSending(true)
    try {
      const result = await post('/api/auth/sms-code', { phone })
      if (!result.ok) { setError(result.error || '验证码发送失败'); return }
      setNotice('验证码已发送，5 分钟内有效')
      setCooldown(RESEND_SECONDS)
      codeInput.current?.focus()
    } catch { setError('网络异常，请稍后重试') } finally { setSending(false) }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(''); setNotice('')
    if (!validPhone(phone)) { setError('请输入正确的手机号'); return }
    if (!/^\d{4,8}$/.test(code.trim())) { setError('请输入短信里的验证码'); return }
    setSubmitting(true)
    try {
      const result = await post('/api/auth/login', { phone, code: code.trim(), inviteCode: inviteCode.trim() })
      if (result.ok) { onSignedIn(); return }
      if (result.code === INVITE_REQUIRED || result.code === INVITE_INVALID) {
        // 验证码没有作废，补上邀请码后可以直接再点一次登录。
        setInviteNeeded(true)
        inviteInput.current?.focus()
      }
      setError(result.error || '登录失败，请重试')
    } catch { setError('网络异常，请稍后重试') } finally { setSubmitting(false) }
  }

  return <main className="companion-shell login-shell">
    <form className="login-card" onSubmit={(event) => void submit(event)} noValidate>
      <p className="kicker">AI PLUSH COMPANION</p>
      <h1>登录</h1>
      <p className="subtle">用手机号登录，开始和你的陪伴角色对话</p>
      <label className="field">手机号
        <input
          type="tel" inputMode="tel" autoComplete="tel" placeholder="请输入手机号" value={phone}
          onChange={(event) => setPhone(event.target.value)} maxLength={20} aria-label="手机号"
        />
      </label>
      <label className="field">验证码
        <span className="field-row">
          <input
            ref={codeInput} type="text" inputMode="numeric" autoComplete="one-time-code" placeholder="6 位验证码"
            value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))} maxLength={8}
            aria-label="验证码"
          />
          <button type="button" className="button" onClick={() => void sendCode()} disabled={sending || cooldown > 0}>
            {cooldown > 0 ? `${cooldown} 秒后重发` : sending ? '发送中' : '获取验证码'}
          </button>
        </span>
      </label>
      <label className="field">邀请码
        <input
          ref={inviteInput} type="text" autoComplete="off" autoCapitalize="characters" spellCheck={false}
          placeholder={inviteNeeded ? '请输入邀请码' : '首次登录需要，已有账号可不填'} value={inviteCode}
          onChange={(event) => setInviteCode(event.target.value)} maxLength={32} aria-label="邀请码"
          aria-invalid={inviteNeeded && !inviteCode.trim()}
        />
      </label>
      {error && <div className="error-banner" role="alert">{error}</div>}
      {notice && !error && <div className="notice-banner" role="status">{notice}</div>}
      <button type="submit" className="button primary login-submit" disabled={submitting}>
        {submitting ? '登录中' : '登录'}
      </button>
      <p className="login-footnote">目前是邀请制内测。没有邀请码时，请联系给你链接的人。</p>
    </form>
  </main>
}
