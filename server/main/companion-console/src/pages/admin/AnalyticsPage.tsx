import { Button, Card, Empty, Space, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useRef, useState } from 'react'

import { getAnalyticsOverview, type AnalyticsOverview, type Cohort, type DailyLatency, type EventCount } from '../../api/analytics'
import { AdminPage } from './AdminPage'
import { adminErrorMessage } from './adminErrors'

const number = new Intl.NumberFormat('zh-CN')
const eventLabels: Record<string, string> = {
  signed_in: '登录成功',
  role_created: '选好了第一个角色',
  mic_started: '打开了麦克风',
  mic_failed: '麦克风打开失败',
  turn_failed: '一轮对话失败',
  reconnect_failed: '重连多次仍失败',
  quota_reached: '用完当天的次数',
  rest_reminder_shown: '出现休息提醒',
  reply_withdrawn: '回复被收回',
  history_cleared: '清空了对话记录',
}

/** 人数加上占这一批注册用户的比例。那一天还没过完时显示“未到”。 */
function retained(value: number | null, size: number) {
  if (value === null) return <Typography.Text type="secondary">未到</Typography.Text>
  if (size === 0) return '-'
  return `${number.format(value)}（${Math.round((value / size) * 100)}%）`
}

/**
 * 内测期间看三件事：用户会不会回来、回复够不够快、哪里出了问题。数据都不含对话内容。
 */
export function AnalyticsPage() {
  const [overview, setOverview] = useState<AnalyticsOverview>({ cohorts: [], latency: [], events: [] })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const mounted = useRef(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const result = await getAnalyticsOverview(30)
      if (mounted.current) setOverview(result)
    } catch (reason) {
      if (mounted.current) setError(adminErrorMessage(reason, '数据加载失败'))
    } finally {
      if (mounted.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void load()
    return () => { mounted.current = false }
  }, [load])

  const cohortColumns: ColumnsType<Cohort> = [
    { title: '注册日期', dataIndex: 'date' },
    { title: '注册人数', dataIndex: 'size', align: 'right', render: (value: number) => number.format(value) },
    { title: '聊过至少一轮', align: 'right', render: (_, row) => retained(row.activated, row.size) },
    { title: '次日留存', align: 'right', render: (_, row) => retained(row.d1, row.size) },
    { title: '7 日留存', align: 'right', render: (_, row) => retained(row.d7, row.size) },
    { title: '30 日留存', align: 'right', render: (_, row) => retained(row.d30, row.size) },
  ]
  const latencyColumns: ColumnsType<DailyLatency> = [
    { title: '日期', dataIndex: 'date' },
    { title: '对话轮数', dataIndex: 'turns', align: 'right', render: (value: number) => number.format(value) },
    { title: '首字耗时中位数', dataIndex: 'p50', align: 'right', render: (value: number) => `${number.format(value)} ms` },
    { title: '首字耗时 90 分位', dataIndex: 'p90', align: 'right', render: (value: number) => `${number.format(value)} ms` },
  ]
  const eventColumns: ColumnsType<EventCount> = [
    { title: '日期', dataIndex: 'date' },
    { title: '事件', render: (_, row) => eventLabels[row.name] ?? row.name },
    { title: '次数', dataIndex: 'total', align: 'right', render: (value: number) => number.format(value) },
    { title: '涉及用户数', dataIndex: 'users', align: 'right', render: (value: number) => number.format(value) },
  ]

  return <AdminPage
    title="留存与体验"
    subTitle="最近 30 天，不包含对话内容"
    error={error}
    onRetry={error ? () => void load() : undefined}
    actions={<Button onClick={() => void load()} loading={loading}>刷新</Button>}
  >
    <Space direction="vertical" size={16} style={{ display: 'flex' }}>
      <Card title="留存">
        <Typography.Paragraph type="secondary">
          按注册日期分组。“7 日留存”指注册后第 7 天当天回来聊过的人数，只算那一天，不是 7 天内任意一天。那一天还没过完时显示“未到”。管理员账号不计入。
        </Typography.Paragraph>
        <Table<Cohort>
          rowKey="date" size="middle" loading={loading} columns={cohortColumns} dataSource={overview.cohorts}
          pagination={false} scroll={{ x: 760 }} locale={{ emptyText: <Empty description="最近 30 天没有新注册的用户" /> }}
        />
      </Card>
      <Card title="回复速度">
        <Typography.Paragraph type="secondary">
          首字耗时是从服务端收到用户输入到出现第一段回复文字的时间，不包含语音合成和网络传输。
        </Typography.Paragraph>
        <Table<DailyLatency>
          rowKey="date" size="middle" loading={loading} columns={latencyColumns} dataSource={overview.latency}
          pagination={false} scroll={{ x: 560 }} locale={{ emptyText: <Empty description="还没有对话" /> }}
        />
      </Card>
      <Card title="关键行为">
        <Table<EventCount>
          rowKey={(row) => `${row.date}-${row.name}`} size="middle" loading={loading} columns={eventColumns}
          dataSource={overview.events} pagination={overview.events.length > 20 ? { pageSize: 20, showSizeChanger: false } : false}
          scroll={{ x: 560 }} locale={{ emptyText: <Empty description="还没有上报的事件" /> }}
        />
      </Card>
    </Space>
  </AdminPage>
}
