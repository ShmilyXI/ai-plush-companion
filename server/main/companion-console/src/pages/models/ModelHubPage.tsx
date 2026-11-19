import { PageContainer } from '@ant-design/pro-components'
import { Tabs } from 'antd'
import { useState } from 'react'

import { ModelChannelsPanel } from './ModelChannelsPanel'
import { ModelManagementPage } from './ModelManagementPage'

type HubTab = 'channels' | 'all'

/**
 * 模型管理入口。对话类模型按“连接、模型、默认模型”三步管理；
 * 语音、记忆等没有统一模型列表的类型，以及连接出现之前的旧配置，
 * 仍在“全部模型配置”里逐个维护。
 */
export function ModelHubPage() {
  const [tab, setTab] = useState<HubTab>('channels')
  return <PageContainer
    className="model-hub-page"
    title={<h1 className="page-container-title">模型管理</h1>}
    subTitle="先接入服务商，再选择模型，最后指定默认模型"
  >
    <Tabs
      activeKey={tab}
      onChange={(value) => setTab(value as HubTab)}
      destroyOnHidden
      items={[
        { key: 'channels', label: '连接与对话模型', children: <ModelChannelsPanel /> },
        { key: 'all', label: '全部模型配置', children: <ModelManagementPage embedded /> },
      ]}
    />
  </PageContainer>
}
