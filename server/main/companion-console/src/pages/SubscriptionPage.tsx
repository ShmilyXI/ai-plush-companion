import { AudioOutlined, DatabaseOutlined, DesktopOutlined, RobotOutlined } from '@ant-design/icons'
import { PageContainer } from '@ant-design/pro-components'
import { Alert, Card, Descriptions, Spin, Statistic, Typography } from 'antd'
import { useEffect, useRef, useState } from 'react'

import { getSubscription, type CompanionSubscription } from '../api/subscription'

function formatExpiry(expiresAt: string) {
  return new Date(expiresAt).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    timeZoneName: 'short',
  })
}

export function SubscriptionPage() {
  const [subscription, setSubscription] = useState<CompanionSubscription | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const mounted = useRef(false)
  useEffect(() => {
    mounted.current = true
    const controller = new AbortController()
    void getSubscription({ signal: controller.signal }).then((data) => { if (mounted.current && !controller.signal.aborted) setSubscription(data) }).catch((reason) => { if (mounted.current && !controller.signal.aborted) setError(reason instanceof Error ? reason.message : '订阅加载失败') }).finally(() => { if (mounted.current && !controller.signal.aborted) setLoading(false) })
    return () => { mounted.current = false; controller.abort() }
  }, [])
  return <PageContainer title={<h1 className="page-container-title">订阅</h1>} subTitle="查看当前套餐和可用权益。普通账号在这里不会改动套餐。">{error && <Alert type="error" showIcon message={error} />}<Spin spinning={loading}>{subscription && <><Card className="surface-card subscription-hero"><Typography.Text type="secondary">当前套餐</Typography.Text><Typography.Title level={2}>{subscription.planName}</Typography.Title><Typography.Paragraph>{subscription.expiresAt ? `有效期至 ${formatExpiry(subscription.expiresAt)}（本地时间）` : '长期有效'}</Typography.Paragraph></Card><div className="subscription-grid"><Card className="surface-card"><Statistic title="设备数量" value={subscription.maxDevices} prefix={<DesktopOutlined />} /></Card><Card className="surface-card"><Statistic title="陪伴角色" value={subscription.maxProfiles} prefix={<RobotOutlined />} /></Card><Card className="surface-card"><Descriptions column={1} items={[{ key: 'memory', label: <><DatabaseOutlined /> 长期记忆</>, children: subscription.longTermMemory ? '已包含' : '未包含' }, { key: 'voice', label: <><AudioOutlined /> 进阶声音</>, children: subscription.advancedVoice ? '已包含' : '未包含' }]} /></Card></div></>}</Spin></PageContainer>
}
