import { PageContainer } from '@ant-design/pro-components'
import { Tabs } from 'antd'
import { lazy, Suspense, useEffect, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'

import { useAuthStore } from '../../auth/authStore'

const TimbreManagementPanel = lazy(() => import('./TimbreManagementPage').then((module) => ({ default: module.TimbreManagementPanel })))
const VoiceClonePanel = lazy(() => import('./VoiceClonePage').then((module) => ({ default: module.VoiceClonePanel })))
const VoiceResourcePanel = lazy(() => import('./VoiceResourcePage').then((module) => ({ default: module.VoiceResourcePanel })))

type VoiceTab = 'timbres' | 'clone' | 'resources'

const tabLabels: Record<VoiceTab, string> = {
  timbres: '音色库',
  clone: '音色克隆',
  resources: '音色资源',
}

function PanelBoundary({ children }: { children: ReactNode }) {
  return <Suspense fallback={<div className="route-loading" role="status" aria-label="正在加载声音管理"><span className="loading-dot" /></div>}>{children}</Suspense>
}

export function VoiceManagementPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const permissions = useAuthStore((state) => state.permissions)
  const isAdmin = permissions.includes('sys:role:superAdmin')
  const allowedTabs: VoiceTab[] = isAdmin ? ['timbres', 'clone', 'resources'] : ['clone']
  const requestedTab = searchParams.get('tab')
  const activeTab = allowedTabs.includes(requestedTab as VoiceTab) ? requestedTab as VoiceTab : allowedTabs[0]

  useEffect(() => {
    if (requestedTab === activeTab) return
    const next = new URLSearchParams(searchParams)
    next.set('tab', activeTab)
    setSearchParams(next, { replace: true })
  }, [activeTab, requestedTab, searchParams, setSearchParams])

  const panels: Record<VoiceTab, ReactNode> = {
    timbres: <PanelBoundary><TimbreManagementPanel /></PanelBoundary>,
    clone: <PanelBoundary><VoiceClonePanel /></PanelBoundary>,
    resources: <PanelBoundary><VoiceResourcePanel /></PanelBoundary>,
  }

  return <PageContainer
    title={<h1 className="page-container-title">声音管理</h1>}
    subTitle="管理 TTS 音色、克隆声音和账号音色资源"
  >
    <Tabs
      activeKey={activeTab}
      destroyOnHidden
      items={allowedTabs.map((tab) => ({ key: tab, label: tabLabels[tab], children: panels[tab] }))}
      onChange={(tab) => {
        const next = new URLSearchParams(searchParams)
        next.set('tab', tab)
        setSearchParams(next)
      }}
    />
  </PageContainer>
}
