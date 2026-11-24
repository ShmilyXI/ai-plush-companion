'use client'

import { useEffect, useRef, useState, type FormEvent } from 'react'
import { CONSENT_VERSION } from '../lib/consent'
import RoleFigure from './RoleFigure'

/** manager-api 的业务错误码：首次登录需要邀请码、邀请码无效。 */
const INVITE_REQUIRED = 10210
const INVITE_INVALID = 10211
/** 图形验证码不对。这时弹窗不关，换一张图让用户再试。 */
const CAPTCHA_WRONG = 10067
const RESEND_SECONDS = 60

interface ApiFailure { error?: string; code?: number }

async function post(path: string, body: Record<string, unknown>) {
  const response = await fetch(path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), cache: 'no-store',
  })
  const payload = await response.json().catch(() => ({})) as ApiFailure
  return { ok: response.ok, error: payload.error || '', code: payload.code }
}

/** 图形验证码的编号。不是秘密，只用来对上是哪一张图；不用 crypto.randomUUID，它在 http 的局域网地址下没有。 */
function newCaptchaId() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
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
  const [agreed, setAgreed] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [sending, setSending] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  // 点“获取验证码”先弹出图形验证码，答对了才发短信。每张图只能核对一次，所以每试一次就换一张。
  const [captchaOpen, setCaptchaOpen] = useState(false)
  const [captchaId, setCaptchaId] = useState('')
  const [captcha, setCaptcha] = useState('')
  const [captchaError, setCaptchaError] = useState('')
  const captchaInput = useRef<HTMLInputElement>(null)
  const codeInput = useRef<HTMLInputElement>(null)
  const inviteInput = useRef<HTMLInputElement>(null)

  function refreshCaptcha() {
    setCaptchaId(newCaptchaId()); setCaptcha('')
  }

  function closeCaptcha() {
    if (sending) return
    setCaptchaOpen(false); setCaptchaError('')
  }

  // 弹窗打开时光标放进输入框，按 Esc 关掉。
  useEffect(() => {
    if (!captchaOpen) return
    captchaInput.current?.focus()
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') closeCaptcha() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  useEffect(() => {
    if (cooldown <= 0) return
    const timer = window.setTimeout(() => setCooldown((value) => value - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [cooldown])

  /** 点“获取验证码”：手机号没问题就弹出图形验证码。 */
  function askForCaptcha() {
    setError(''); setNotice('')
    if (!validPhone(phone)) { setError('请输入正确的手机号'); return }
    refreshCaptcha(); setCaptchaError(''); setCaptchaOpen(true)
  }

  /** 弹窗里点“发送”：带着图形验证码发短信。 */
  async function sendCode() {
    if (!captcha.trim()) { setCaptchaError('请输入图片里的字符'); captchaInput.current?.focus(); return }
    setSending(true); setCaptchaError('')
    try {
      const result = await post('/api/auth/sms-code', { phone, captchaId, captcha: captcha.trim() })
      if (result.ok) {
        setCaptchaOpen(false)
        setNotice('验证码已发送，5 分钟内有效')
        setCooldown(RESEND_SECONDS)
        codeInput.current?.focus()
        return
      }
      if (result.code === CAPTCHA_WRONG) {
        // 这张图已经作废了，换一张接着试。
        refreshCaptcha()
        setCaptchaError(result.error || '图形验证码错误，换了一张，请再输一次')
        captchaInput.current?.focus()
        return
      }
      // 其他原因（发得太频繁、手机号不对）跟图无关，关掉弹窗在表单里说。
      setCaptchaOpen(false)
      setError(result.error || '验证码发送失败')
    } catch { setCaptchaError('网络异常，请稍后重试'); refreshCaptcha() } finally { setSending(false) }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(''); setNotice('')
    if (!validPhone(phone)) { setError('请输入正确的手机号'); return }
    if (!/^\d{4,8}$/.test(code.trim())) { setError('请输入短信里的验证码'); return }
    if (!agreed) { setError('请先确认已满 18 周岁，并阅读同意用户协议和隐私政策'); return }
    setSubmitting(true)
    try {
      const result = await post('/api/auth/login', {
        phone, code: code.trim(), inviteCode: inviteCode.trim(), consentVersion: CONSENT_VERSION,
      })
      if (result.ok) { onSignedIn(); return }
      if (result.code === INVITE_REQUIRED || result.code === INVITE_INVALID) {
        // 验证码没有作废，补上邀请码后可以直接再点一次登录。
        setInviteNeeded(true)
        inviteInput.current?.focus()
      }
      setError(result.error || '登录失败，请重试')
    } catch { setError('网络异常，请稍后重试') } finally { setSubmitting(false) }
  }

  return <main className="welcome">
    <div className="ambient" aria-hidden="true" />
    <section className="welcome-art">
      <div className="hero-trio">
        <RoleFigure name="阿澈" size={128} />
        <RoleFigure name="紫萱" size={168} />
        <RoleFigure name="小满" size={128} />
      </div>
      <p className="welcome-line">累了、乱了、想说话了，来坐一会儿。</p>
      <p className="welcome-sub">选一个陪伴角色，打字或者直接开口说。它会记得你们聊过的事。</p>
    </section>
    <form className="login-card" onSubmit={(event) => void submit(event)} noValidate>
      <h1>登录 / 注册</h1>
      <p className="subtle">输入手机号和验证码即可。还没有账号的手机号会自动注册</p>
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
          <button type="button" className="button" onClick={askForCaptcha} disabled={sending || cooldown > 0}>
            {cooldown > 0 ? `${cooldown} 秒后重发` : sending ? '发送中' : '获取验证码'}
          </button>
        </span>
      </label>
      <label className="field">邀请码
        <input
          ref={inviteInput} type="text" autoComplete="off" autoCapitalize="characters" spellCheck={false}
          placeholder={inviteNeeded ? '请输入邀请码' : '注册时需要，已有账号可不填'} value={inviteCode}
          onChange={(event) => setInviteCode(event.target.value)} maxLength={32} aria-label="邀请码"
          aria-invalid={inviteNeeded && !inviteCode.trim()}
        />
      </label>
      <label className="consent">
        <input type="checkbox" checked={agreed} onChange={(event) => setAgreed(event.target.checked)} />
        <span>我已满 18 周岁，并已阅读和同意<a href="/terms" target="_blank" rel="noreferrer">《用户协议》</a>和<a href="/privacy" target="_blank" rel="noreferrer">《隐私政策》</a></span>
      </label>
      {error && <div className="error-banner" role="alert">{error}</div>}
      {notice && !error && <div className="notice-banner" role="status">{notice}</div>}
      <button type="submit" className="button primary login-submit" disabled={submitting}>
        {submitting ? '正在登录' : '登录 / 注册'}
      </button>
      <p className="login-footnote">目前是邀请制内测。没有邀请码时，请联系给你链接的人。</p>
    </form>
    {captchaOpen && <div className="captcha-backdrop" onClick={closeCaptcha}>
      <form
        className="captcha-dialog" role="dialog" aria-modal="true" aria-label="图形验证码"
        onClick={(event) => event.stopPropagation()}
        onSubmit={(event) => { event.preventDefault(); void sendCode() }}
      >
        <h2>请输入图片里的字符</h2>
        <button type="button" className="captcha-image" onClick={refreshCaptcha} aria-label="换一张图形验证码" title="看不清？点一下换一张">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={`/api/auth/captcha?id=${captchaId}`} alt="" width={150} height={40} />
        </button>
        <p className="captcha-hint">看不清？点图片换一张</p>
        <input
          ref={captchaInput} type="text" autoComplete="off" autoCapitalize="off" spellCheck={false}
          placeholder="不区分大小写" value={captcha} onChange={(event) => { setCaptcha(event.target.value); setCaptchaError('') }}
          maxLength={8} aria-label="图片里的字符"
        />
        {captchaError && <div className="error-banner" role="alert">{captchaError}</div>}
        <div className="captcha-actions">
          <button type="button" className="button" onClick={closeCaptcha} disabled={sending}>取消</button>
          <button type="submit" className="button primary" disabled={sending}>{sending ? '发送中' : '发送验证码'}</button>
        </div>
      </form>
    </div>}
  </main>
}
