import { Button, Card, Empty, Space, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useRef, useState } from 'react'

import { listDailyUsage, listUserUsage, type DailyUsage, type UserUsage } from '../../api/usage'
import { AdminPage } from './AdminPage'
import { adminErrorMessage } from './adminErrors'

const number = new Intl.NumberFormat('zh-CN')

function tokens(row: { promptTokens: number; completionTokens: number }) {
  // 服务商不报告 token 时两项都是 0，显示成“未知”而不是 0，避免误以为没有消耗。
  if (row.promptTokens === 0 && row.completionTokens === 0) return '未知'
  return `${number.format(row.promptTokens)} / ${number.format(row.completionTokens)}`
}

/**
 * 对话用量。每完成一轮对话记一次，只有数量和耗时，没有对话内容。
 */
export function UsagePage() {
  const [days, setDays] = useState<DailyUsage[]>([])
  const [selectedDate, setSelectedDate] = useState('')
  const [users, setUsers] = useState<UserUsage[]>([])
  const [loading, setLoading] = useState(true)
  const [usersLoading, setUsersLoading] = useState(false)
  const [error, setError] = useState('')
  const mounted = useRef(false)
  const userSequence = useRef(0)

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const result = await listDailyUsage(14)
      if (!mounted.current) return
      setDays(result)
      setSelectedDate((current) => current || result[0]?.usageDate || '')
    } catch (reason) {
      if (mounted.current) setError(adminErrorMessage(reason, '用量加载失败'))
    } finally {
      if (mounted.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void load()
    return () => { mounted.current = false }
  }, [load])

  useEffect(() => {
    if (!selectedDate) { setUsers([]); return }
    const sequence = ++userSequence.current
    setUsersLoading(true)
    listUserUsage(selectedDate)
      .then((result) => { if (mounted.current && userSequence.current === sequence) setUsers(result) })
      .catch((reason: unknown) => {
        if (mounted.current && userSequence.current === sequence) setError(adminErrorMessage(reason, '用户用量加载失败'))
      })
      .finally(() => { if (mounted.current && userSequence.current === sequence) setUsersLoading(false) })
  }, [selectedDate])

  const dayColumns: ColumnsType<DailyUsage> = [
    {
      title: '日期',
      dataIndex: 'usageDate',
      render: (value: string) => <Button
        type="link"
        size="small"
        aria-label={`查看 ${value} 的用户用量`}
        aria-pressed={value === selectedDate}
        onClick={() => setSelectedDate(value)}
      >{value}</Button>,
    },
    { title: '活跃用户', dataIndex: 'users', align: 'right', render: (value: number) => number.format(value) },
    { title: '对话轮数', dataIndex: 'turns', align: 'right', render: (value: number) => number.format(value) },
    { title: '其中语音', dataIndex: 'audioTurns', align: 'right', render: (value: number) => number.format(value) },
    { title: '输入 / 输出 token', align: 'right', render: (_, row) => tokens(row) },
    { title: '语音合成字数', dataIndex: 'ttsChars', align: 'right', render: (value: number) => number.format(value) },
    {
      title: '平均首字耗时',
      dataIndex: 'avgFirstTextMs',
      align: 'right',
      render: (value: number | null) => value === null ? '-' : `${number.format(value)} ms`,
    },
  ]

  const userColumns: ColumnsType<UserUsage> = [
    { title: '用户 ID', dataIndex: 'userId' },
    { title: '对话轮数', dataIndex: 'turns', align: 'right', render: (value: number) => number.format(value) },
    { title: '其中语音', dataIndex: 'audioTurns', align: 'right', render: (value: number) => number.format(value) },
    { title: '输入 / 输出 token', align: 'right', render: (_, row) => tokens(row) },
  ]

  return <AdminPage
    title="对话用量"
    subTitle="每完成一轮对话记一次，不包含对话内容"
    error={error}
    onRetry={error ? () => void load() : undefined}
    actions={<Button onClick={() => void load()} loading={loading}>刷新</Button>}
  >
    <Space direction="vertical" size={16} style={{ display: 'flex' }}>
      <Card title="最近 14 天">
        <Typography.Paragraph type="secondary">
          每个登录用户每天的对话轮数上限在“参数管理”的 app.daily_turn_limit 里设置，0 表示不限制。
        </Typography.Paragraph>
        <Table<DailyUsage>
          rowKey="usageDate"
          size="middle"
          loading={loading}
          columns={dayColumns}
          dataSource={days}
          pagination={false}
          scroll={{ x: 820 }}
          locale={{ emptyText: <Empty description="还没有对话用量" /> }}
        />
      </Card>
      <Card title={selectedDate ? `${selectedDate} 用得最多的用户` : '用得最多的用户'}>
        <Table<UserUsage>
          rowKey="userId"
          size="middle"
          loading={usersLoading}
          columns={userColumns}
          dataSource={users}
          pagination={false}
          scroll={{ x: 560 }}
          locale={{ emptyText: <Empty description={selectedDate ? '这一天没有对话' : '先在上面选一天'} /> }}
        />
      </Card>
    </Space>
  </AdminPage>
}
