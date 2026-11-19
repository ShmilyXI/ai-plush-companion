import { ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components'
import { Alert, Tag } from 'antd'
import { useCallback, useRef, useState } from 'react'

import { listSafetyEvents, type SafetyEvent } from '../../api/safetyEvents'
import { AdminPage } from './AdminPage'
import { adminErrorMessage } from './adminErrors'

const categoryLabels: Record<string, string> = {
  self_harm: '自伤风险',
  illegal_instructions: '违法方法',
  minor_sexual: '涉未成年人的性内容',
  sexual_explicit: '露骨的性内容',
  blocked_term: '运营追加的违禁词',
}

/**
 * 内容安全事件。每条只有类别和处理方式，没有对话内容，供运营了解规则命中的情况。
 */
export function SafetyEventPage() {
  const [error, setError] = useState('')
  const actionRef = useRef<ActionType>(null)

  const request = useCallback(async (params: { current?: number; pageSize?: number }) => {
    setError('')
    try {
      const result = await listSafetyEvents(params.current ?? 1, params.pageSize ?? 20)
      return { data: result.list, total: result.total, success: true }
    } catch (reason) {
      setError(adminErrorMessage(reason, '安全事件加载失败'))
      return { data: [], total: 0, success: false }
    }
  }, [])

  const columns: ProColumns<SafetyEvent>[] = [
    { title: '时间', width: 190, render: (_, row) => row.createdAt ? new Date(row.createdAt).toLocaleString() : '-' },
    { title: '类别', render: (_, row) => categoryLabels[row.category] ?? row.category },
    { title: '命中位置', width: 110, render: (_, row) => row.direction === 'input' ? '用户输入' : '模型回复' },
    {
      title: '处理方式',
      width: 150,
      render: (_, row) => row.action === 'crisis'
        ? <Tag color="orange">给出求助信息</Tag>
        : <Tag color="red">{row.direction === 'input' ? '拒绝回应' : '收回回复'}</Tag>,
    },
    { title: '用户 ID', dataIndex: 'userId' },
  ]

  return <AdminPage
    title="安全事件"
    subTitle="内容安全规则命中的记录，不包含对话内容"
    error={error}
    onRetry={error ? () => void actionRef.current?.reload() : undefined}
  >
    <Alert
      type="info"
      showIcon
      style={{ marginBottom: 16 }}
      message="这里的规则只是基线：按关键词和句式判断，覆盖面有限。对外开放前应当接入专业的内容审核服务。追加违禁词请到“参数管理”修改 app.safety_blocked_terms。"
    />
    <ProTable<SafetyEvent>
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      request={request}
      search={false}
      options={false}
      pagination={{ defaultPageSize: 20, showSizeChanger: false }}
      scroll={{ x: 760 }}
    />
  </AdminPage>
}
