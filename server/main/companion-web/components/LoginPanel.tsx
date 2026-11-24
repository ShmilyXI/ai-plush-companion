'use client'

import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { CONSENT_VERSION } from '../lib/consent'
import { PASSWORD_RULE, encryptPassword, passwordProblem } from '../lib/password'
import RoleFigure from './RoleFigure'

/** manager-api 的业务错误码。 */
const INVITE_REQUIRED = 10210
const INVITE_INVALID = 10211
const PHONE_ALREADY_REGISTERED = 10070
const PHONE_NOT_REGISTERED = 10071
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

/**
 * 登录和注册。注册要手机号、短信验证码、密码和邀请码；登录可以用验证码，也可以用密码。
 * 用验证码登录一个还没注册的手机号时，转到注册，手机号和验证码都带过去。
 */
export default function LoginPanel({ onSignedIn }: { onSignedIn: () => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [method, setMethod] = useState<'code' | 'password'>('code')
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
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
  const passwordInput = useRef<HTMLInputElement>(null)
  const inviteInput = useRef<HTMLInputElement>(null)
  const usesCode = mode === 'register' || method === 'code'

  function switchTo(next: 'login' | 'register', nextMethod: 'code' | 'password' = method) {
    setMode(next); setMethod(nextMethod); setError(''); setNotice('')
  }

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
    if (usesCode && !/^\d{4,8}$/.test(code.trim())) { setError('请输入短信里的验证码'); return }
    if (mode === 'login' && method === 'password' && !password) { setError('请输入密码'); passwordInput.current?.focus(); return }
    if (mode === 'register') {
      const problem = passwordProblem(password)
      if (problem) { setError(problem); passwordInput.current?.focus(); return }
    }
    if (!agreed) { setError('请先确认已满 18 周岁，并阅读同意用户协议和隐私政策'); return }
    setSubmitting(true)
    try {
      const result = mode === 'register'
        ? await post('/api/auth/register', {
          phone, code: code.trim(), password: await encryptPassword(password),
          inviteCode: inviteCode.trim(), consentVersion: CONSENT_VERSION,
        })
        : method === 'password'
          ? await post('/api/auth/login-password', { phone, password: await encryptPassword(password) })
          : await post('/api/auth/login', { phone, code: code.trim(), consentVersion: CONSENT_VERSION })
      if (result.ok) { onSignedIn(); return }
      if (mode === 'login' && method === 'code' && result.code === PHONE_NOT_REGISTERED) {
        // 验证码还有效，带着手机号和验证码去注册，只差设个密码。
        switchTo('register')
        setNotice('这个手机号还没有注册。设置一个密码，就能完成注册')
        // 密码框要等切到注册后才出现。
        requestAnimationFrame(() => passwordInput.current?.focus())
        return
      }
      if (mode === 'register' && result.code === PHONE_ALREADY_REGISTERED) {
        switchTo('login', 'code')
        setNotice('这个手机号已经注册过了，直接登录就行')
        return
      }
      if (result.code === INVITE_REQUIRED || result.code === INVITE_INVALID) {
        // 验证码没有作废，补上邀请码后可以直接再点一次注册。
        setInviteNeeded(true)
        inviteInput.current?.focus()
      }
      setError(result.error || (mode === 'register' ? '注册失败，请重试' : '登录失败，请重试'))
    } catch (reason) {
      setError(reason instanceof Error && reason.message ? reason.message : '网络异常，请稍后重试')
    } finally { setSubmitting(false) }
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
      <h1 className="sr-only">登录 / 注册</h1>
      <div className="auth-tabs" role="tablist" aria-label="登录或注册">
        <button type="button" role="tab" aria-selected={mode === 'login'} onClick={() => switchTo('login')}>登录</button>
        <button type="button" role="tab" aria-selected={mode === 'register'} onClick={() => switchTo('register')}>注册</button>
      </div>
      <p className="subtle">{mode === 'register'
        ? '用手机号注册，设置一个密码。以后可以用验证码或密码登录'
        : method === 'code' ? '用手机号和短信验证码登录' : '用手机号和密码登录'}</p>
      <label className="field">手机号
        <input
          type="tel" inputMode="tel" autoComplete="tel" placeholder="请输入手机号" value={phone}
          onChange={(event) => setPhone(event.target.value)} maxLength={20} aria-label="手机号"
        />
      </label>
      {usesCode && <label className="field">验证码
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
      </label>}
      {(mode === 'register' || method === 'password') && <label className="field">{mode === 'register' ? '设置密码' : '密码'}
        <span className="password-box">
          <input
            ref={passwordInput} type={showPassword ? 'text' : 'password'}
            autoComplete={mode === 'register' ? 'new-password' : 'current-password'}
            placeholder={mode === 'register' ? PASSWORD_RULE : '请输入密码'} value={password}
            onChange={(event) => setPassword(event.target.value)} maxLength={32} aria-label={mode === 'register' ? '设置密码' : '密码'}
          />
          <button type="button" className="password-eye" onClick={() => setShowPassword((value) => !value)} aria-label={showPassword ? '隐藏密码' : '显示密码'}>
            {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
          </button>
        </span>
      </label>}
      {mode === 'register' && <label className="field">邀请码
        <input
          ref={inviteInput} type="text" autoComplete="off" autoCapitalize="characters" spellCheck={false}
          placeholder="内测期间注册需要邀请码" value={inviteCode}
          onChange={(event) => setInviteCode(event.target.value)} maxLength={32} aria-label="邀请码"
          aria-invalid={inviteNeeded && !inviteCode.trim()}
        />
      </label>}
      {mode === 'login' && <button type="button" className="link-button auth-switch" onClick={() => switchTo('login', method === 'code' ? 'password' : 'code')}>
        {method === 'code' ? '用密码登录' : '忘记密码？用验证码登录'}
      </button>}
      <label className="consent">
        <input type="checkbox" checked={agreed} onChange={(event) => setAgreed(event.target.checked)} />
        <span>我已满 18 周岁，并已阅读和同意<a href="/terms" target="_blank" rel="noreferrer">《用户协议》</a>和<a href="/privacy" target="_blank" rel="noreferrer">《隐私政策》</a></span>
      </label>
      {error && <div className="error-banner" role="alert">{error}</div>}
      {notice && !error && <div className="notice-banner" role="status">{notice}</div>}
      <button type="submit" className="button primary login-submit" disabled={submitting}>
        {submitting ? (mode === 'register' ? '正在注册' : '正在登录') : mode === 'register' ? '注册' : '登录'}
      </button>
      <p className="login-footnote">目前是邀请制内测。没有邀请码时，请联系给你链接的人，也可以加 <span style={{ whiteSpace: 'nowrap' }}>QQ：1262260527</span>。</p>
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
