import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Spin } from 'antd'

interface BootstrapResponse { code: string }
interface CompanionWebViewProps {
  appUrl: string
  requestBootstrap: () => Promise<BootstrapResponse>
}

function originOf(value: string) {
  try { return new URL(value, window.location.href).origin } catch { return '' }
}

export function CompanionWebView({ appUrl, requestBootstrap }: CompanionWebViewProps) {
  const frameRef = useRef<HTMLIFrameElement>(null)
  const [error, setError] = useState('')
  const [ready, setReady] = useState(false)
  const bootstrapSentRef = useRef(false)
  const appOrigin = originOf(appUrl)

  const sendBootstrap = useCallback(async () => {
    if (!appOrigin || bootstrapSentRef.current) return
    bootstrapSentRef.current = true
    try {
      const bootstrap = await requestBootstrap()
      frameRef.current?.contentWindow?.postMessage(
        { type: 'companion.bootstrap', code: bootstrap.code }, appOrigin,
      )
    } catch (reason) {
      bootstrapSentRef.current = false
      setError(reason instanceof Error ? reason.message : '登录桥接失败')
    }
  }, [appOrigin, requestBootstrap])

  useEffect(() => {
    if (!appOrigin) { setError('外部应用地址无效'); return }
    let disposed = false
    const handleMessage = (event: MessageEvent) => {
      if (event.origin !== appOrigin || event.source !== frameRef.current?.contentWindow) return
      if (event.data?.type === 'companion.ready') void sendBootstrap()
    }
    window.addEventListener('message', handleMessage)
    return () => { disposed = true; window.removeEventListener('message', handleMessage) }
  }, [appOrigin, sendBootstrap])

  if (error) return <Alert type="error" showIcon message={error} />
  return <div className="companion-web-view"><div className="companion-web-view-loading" hidden={ready}><Spin /> 正在打开陪伴对话</div><iframe ref={frameRef} title="陪伴对话" src={appUrl} onLoad={() => { setReady(true); void sendBootstrap() }} allow="microphone; autoplay" /></div>
}
