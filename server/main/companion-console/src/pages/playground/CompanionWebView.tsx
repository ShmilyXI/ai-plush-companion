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
  const pendingRef = useRef(false)
  const appOrigin = originOf(appUrl)

  // 只在网页端说“准备好了”之后才发登录码。iframe 的 load 事件比网页端开始监听消息早，那时发过去会被丢掉。
  // 网页端每次加载（包括在里面刷新）都会再说一次，每次给一个新的码，码只能用一次。
  const sendBootstrap = useCallback(async () => {
    if (!appOrigin || pendingRef.current) return
    pendingRef.current = true
    try {
      const bootstrap = await requestBootstrap()
      frameRef.current?.contentWindow?.postMessage(
        { type: 'companion.bootstrap', code: bootstrap.code }, appOrigin,
      )
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '登录桥接失败')
    } finally {
      pendingRef.current = false
    }
  }, [appOrigin, requestBootstrap])

  useEffect(() => {
    if (!appOrigin) { setError('外部应用地址无效'); return }
    const handleMessage = (event: MessageEvent) => {
      if (event.origin !== appOrigin || event.source !== frameRef.current?.contentWindow) return
      if (event.data?.type === 'companion.ready') void sendBootstrap()
    }
    window.addEventListener('message', handleMessage)
    return () => { window.removeEventListener('message', handleMessage) }
  }, [appOrigin, sendBootstrap])

  if (error) return <Alert type="error" showIcon message={error} />
  return <div className="companion-web-view"><div className="companion-web-view-loading" hidden={ready}><Spin /> 正在打开陪伴对话</div><iframe ref={frameRef} title="陪伴对话" src={appUrl} onLoad={() => setReady(true)} allow="microphone; autoplay" /></div>
}
