import { useCallback, useEffect, useRef, useState } from 'react'

import http, { ApiError } from '../api/http'

export interface CaptchaState {
  id: string
  url: string
  loading: boolean
  error: string
}

function createCaptchaId() {
  return globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export function useCaptcha() {
  const [captcha, setCaptcha] = useState<CaptchaState>({ id: '', url: '', loading: true, error: '' })
  const sequence = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const currentUrl = useRef('')

  const refresh = useCallback(async () => {
    const requestSequence = ++sequence.current
    controller.current?.abort()
    const nextController = new AbortController()
    controller.current = nextController
    const nextId = createCaptchaId()
    setCaptcha((value) => ({ ...value, loading: true, error: '' }))
    try {
      const response = await http.get<Blob>(`/user/captcha?uuid=${encodeURIComponent(nextId)}`, {
        responseType: 'blob',
        signal: nextController.signal,
        headers: { 'Cache-Control': 'no-cache' },
      })
      if (nextController.signal.aborted || requestSequence !== sequence.current) return
      const nextUrl = URL.createObjectURL(response.data)
      if (currentUrl.current) URL.revokeObjectURL(currentUrl.current)
      currentUrl.current = nextUrl
      setCaptcha({ id: nextId, url: nextUrl, loading: false, error: '' })
    } catch (error) {
      if (nextController.signal.aborted || requestSequence !== sequence.current) return
      setCaptcha((value) => ({
        ...value,
        loading: false,
        error: error instanceof ApiError ? error.message : '验证码加载失败，请刷新重试',
      }))
    }
  }, [])

  useEffect(() => {
    void refresh()
    return () => {
      sequence.current += 1
      controller.current?.abort()
      if (currentUrl.current) URL.revokeObjectURL(currentUrl.current)
      currentUrl.current = ''
    }
  }, [refresh])

  return { captcha, refresh }
}
