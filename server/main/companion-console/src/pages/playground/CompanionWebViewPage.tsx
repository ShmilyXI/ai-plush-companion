import { Alert, Typography } from 'antd'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { CompanionWebView } from './CompanionWebView'
import http, { type ApiResult } from '../../api/http'

interface BootstrapResponse { code: string }

export function CompanionWebViewPage() {
  const appUrl = import.meta.env.VITE_COMPANION_WEB_URL || 'http://127.0.0.1:8010'
  const [error, setError] = useState('')
  const [handoff, setHandoff] = useState(false)
  const requestBootstrap = useCallback(async () => {
    const response = await http.post<ApiResult<BootstrapResponse>>('/api/v1/web-sessions/bootstrap', {
      audience: 'companion-web', origin: new URL(appUrl).origin,
    })
    if (response.data.code !== 0 || !response.data.data?.code) throw new Error(response.data.msg || '登录桥接失败')
    return response.data.data
  }, [appUrl])
  const safeUrl = useMemo(() => {
    try { return new URL(appUrl).toString() } catch { return '' }
  }, [appUrl])
  useEffect(() => { if (!safeUrl) setError('外部应用地址无效') }, [safeUrl])
  useEffect(() => {
    const returnTo = new URLSearchParams(window.location.search).get('companion_return')
    if (!returnTo || !safeUrl) return
    setHandoff(true)
    try {
      const target = new URL(returnTo)
      if (target.origin !== new URL(safeUrl).origin) return
      void requestBootstrap().then(({ code }) => {
        target.searchParams.set('code', code)
        window.location.assign(target.toString())
      }).catch((reason) => setError(reason instanceof Error ? reason.message : '登录桥接失败'))
    } catch { setError('返回地址无效') }
  }, [requestBootstrap, safeUrl])
  return <div className="console-page">
    <div className="page-heading"><div><h1>操练场</h1><Typography.Text type="secondary">独立陪伴对话应用</Typography.Text></div></div>
    {error && <Alert type="error" showIcon message={error} />}
    {!handoff && safeUrl && <CompanionWebView appUrl={safeUrl} requestBootstrap={requestBootstrap} />}
  </div>
}
