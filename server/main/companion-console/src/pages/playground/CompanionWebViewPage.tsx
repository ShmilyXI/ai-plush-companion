import { Alert, Button } from 'antd'
import { ExportOutlined } from '@ant-design/icons'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { CompanionWebView } from './CompanionWebView'
import { companionWebUrl, openCompanionWeb, requestWebBootstrap } from '../../app/companionWeb'
import { routeTitle } from '../../app/navigation'
import { AdminPage } from '../admin/AdminPage'

/**
 * 在管理台里直接用网页端：以当前管理台账号登录，不用再输一次手机号。
 * 也可以在新窗口里打开，麦克风和角色动画在独立窗口里体验更完整。
 */
export function CompanionWebViewPage() {
  const appUrl = companionWebUrl()
  const [error, setError] = useState('')
  const [handoff, setHandoff] = useState(false)
  const requestBootstrap = useCallback(() => requestWebBootstrap(appUrl), [appUrl])
  const safeUrl = useMemo(() => {
    try { return new URL(appUrl).toString() } catch { return '' }
  }, [appUrl])
  useEffect(() => { if (!safeUrl) setError('网页端地址无效，请检查 VITE_COMPANION_WEB_URL') }, [safeUrl])
  // 网页端把没登录的人送到这里，带着要回去的地址：要一个启动码再送回去。
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
  return <AdminPage
    title={routeTitle('webApp')}
    subTitle="以当前管理台账号使用网页端，不用再登录"
    actions={safeUrl ? <Button icon={<ExportOutlined />} onClick={() => void openCompanionWeb(appUrl)}>在新窗口打开</Button> : undefined}
  >
    {error && <Alert type="error" showIcon message={error} />}
    {!handoff && safeUrl && <CompanionWebView appUrl={safeUrl} requestBootstrap={requestBootstrap} />}
  </AdminPage>
}
