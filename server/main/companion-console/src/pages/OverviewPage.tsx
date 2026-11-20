import { Alert, Button, Card, Col, Empty, Row, Space, Statistic, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { getAnalyticsOverview, type AnalyticsOverview } from '../api/analytics'
import { listDailyUsage, type DailyUsage } from '../api/usage'
import { consoleRouteByKey, routeTitle } from '../app/navigation'
import { useAuthStore } from '../auth/authStore'
import { AdminPage } from './admin/AdminPage'
import { adminErrorMessage } from './admin/adminErrors'

const DAYS = 7
const number = new Intl.NumberFormat('zh-CN')

function companionWebUrl() {
  return import.meta.env.VITE_COMPANION_WEB_URL || 'http://127.0.0.1:8010'
}

/** 本地日期，写成服务端统计用的 YYYY-MM-DD。 */
function dateKey(date: Date) {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function seconds(ms: number) {
  return `${(ms / 1000).toFixed(1)} 秒`
}

/**
 * 网页对话的运营概览：今天有多少人在聊、最近来了多少新用户、回复快不快。
 * 只有数量和耗时，没有对话内容。
 */
export function OverviewPage() {
  const adminPermission = consoleRouteByKey('analytics').permission
  const isAdmin = useAuthStore((state) => !adminPermission || state.hasPermission(adminPermission))
  const [usage, setUsage] = useState<DailyUsage[] | null>(null)
  const [analytics, setAnalytics] = useState<AnalyticsOverview | null>(null)
  const [loading, setLoading] = useState(isAdmin)
  const [error, setError] = useState('')
  const sequence = useRef(0)
  const navigate = useNavigate()

  const load = useCallback(async () => {
    const current = ++sequence.current
    setLoading(true)
    setError('')
    const [usageResult, analyticsResult] = await Promise.allSettled([listDailyUsage(DAYS), getAnalyticsOverview(DAYS)])
    if (current !== sequence.current) return
    setUsage(usageResult.status === 'fulfilled' ? usageResult.value : null)
    setAnalytics(analyticsResult.status === 'fulfilled' ? analyticsResult.value : null)
    const failure = usageResult.status === 'rejected' ? usageResult.reason
      : analyticsResult.status === 'rejected' ? analyticsResult.reason : null
    if (failure) setError(adminErrorMessage(failure, '概览数据加载失败'))
    setLoading(false)
  }, [])

  useEffect(() => {
    if (isAdmin) void load()
    return () => { sequence.current += 1 }
  }, [isAdmin, load])

  if (!isAdmin) {
    return <AdminPage title={routeTitle('dashboard')}>
      <Alert
        type="info"
        showIcon
        message="这个账号没有管理权限"
        description="管理台用来配置角色、模型和查看运营数据。和角色聊天请使用网页端。"
        action={<Button type="primary" href={companionWebUrl()} target="_blank" rel="noreferrer">打开网页端</Button>}
      />
    </AdminPage>
  }

  const today = dateKey(new Date())
  const since = dateKey(new Date(Date.now() - (DAYS - 1) * 24 * 60 * 60 * 1000))
  const todayUsage = usage?.find((row) => row.usageDate === today)
  const newUsers = analytics?.cohorts.filter((cohort) => cohort.date >= since).reduce((sum, cohort) => sum + cohort.size, 0)
  const latestLatency = analytics?.latency.reduce<AnalyticsOverview['latency'][number] | null>(
    (latest, row) => (!latest || row.date > latest.date ? row : latest), null,
  )

  const columns: ColumnsType<DailyUsage> = [
    { title: '日期', dataIndex: 'usageDate' },
    { title: '聊过的用户', dataIndex: 'users', align: 'right', render: (value: number) => number.format(value) },
    { title: '对话轮数', dataIndex: 'turns', align: 'right', render: (value: number) => number.format(value) },
    { title: '其中语音', dataIndex: 'audioTurns', align: 'right', render: (value: number) => number.format(value) },
    { title: '开场白', dataIndex: 'openings', align: 'right', render: (value: number) => number.format(value) },
  ]

  return <AdminPage
    title={routeTitle('dashboard')}
    subTitle="网页对话今天的情况，不包含对话内容"
    error={error}
    onRetry={error ? () => void load() : undefined}
    actions={<Button onClick={() => void load()} loading={loading}>刷新</Button>}
  >
    <Space direction="vertical" size={16} style={{ display: 'flex' }}>
      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}>
          <Card loading={loading}>
            <Statistic title="今天聊过的用户" value={usage ? todayUsage?.users ?? 0 : '不可用'} />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card loading={loading}>
            <Statistic title="今天的对话轮数" value={usage ? todayUsage?.turns ?? 0 : '不可用'} />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card loading={loading}>
            <Statistic title={`近 ${DAYS} 天新用户`} value={newUsers ?? '不可用'} />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card loading={loading}>
            <Statistic
              title="首字耗时中位数"
              value={latestLatency ? seconds(latestLatency.p50) : analytics ? '暂无' : '不可用'}
              suffix={latestLatency ? <small className="overview-stat-note">{latestLatency.date}</small> : undefined}
            />
          </Card>
        </Col>
      </Row>
      <Card title={`最近 ${DAYS} 天`} extra={<Link to="/admin/usage">查看对话用量</Link>}>
        <Table<DailyUsage>
          rowKey="usageDate" size="middle" loading={loading} columns={columns} dataSource={usage ?? []}
          pagination={false} scroll={{ x: 520 }} locale={{ emptyText: <Empty description={`最近 ${DAYS} 天没有对话`} /> }}
        />
      </Card>
      <Card title="常用操作">
        <Space wrap>
          <Button onClick={() => navigate('/admin/invite-codes')}>生成邀请码</Button>
          <Button onClick={() => navigate('/admin/analytics')}>查看留存</Button>
          <Button onClick={() => navigate('/admin/safety-events')}>查看安全事件</Button>
          <Button href={companionWebUrl()} target="_blank" rel="noreferrer">打开网页端</Button>
        </Space>
      </Card>
    </Space>
  </AdminPage>
}
