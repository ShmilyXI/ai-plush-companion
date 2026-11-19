import { ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components'
import { Alert, Button, DatePicker, Form, Input, InputNumber, Modal, Space, Switch, Tag, Typography, message } from 'antd'
import type dayjs from 'dayjs'
import { useCallback, useEffect, useRef, useState } from 'react'

import {
  createInviteCodes,
  getInviteRequired,
  listInviteCodes,
  setInviteCodeEnabled,
  setInviteRequired,
  type InviteCode,
} from '../../api/inviteCodes'
import { AdminPage } from './AdminPage'
import { adminErrorMessage } from './adminErrors'

interface CreateValues {
  count: number
  maxUses: number
  expireDate?: dayjs.Dayjs | null
  remark?: string
}

function statusTag(row: InviteCode) {
  if (!row.enabled) return <Tag>已停用</Tag>
  if (row.usable) return <Tag color="green">可用</Tag>
  return <Tag color="orange">{row.usedCount >= row.maxUses ? '已用完' : '已过期'}</Tag>
}

/**
 * 邀请码。开启邀请制后，新手机号必须凭邀请码才能创建账号，已有账号登录不受影响。
 */
export function InviteCodePage() {
  const [required, setRequired] = useState<boolean | null>(null)
  const [error, setError] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [createError, setCreateError] = useState('')
  const [saving, setSaving] = useState(false)
  const [created, setCreated] = useState<InviteCode[]>([])
  const [busyId, setBusyId] = useState<string | null>(null)
  const [form] = Form.useForm<CreateValues>()
  const [messageApi, messageContext] = message.useMessage()
  const actionRef = useRef<ActionType>(null)
  const mounted = useRef(false)

  const loadRequired = useCallback(async () => {
    try {
      const value = await getInviteRequired()
      if (mounted.current) setRequired(value)
    } catch (reason) {
      if (mounted.current) setError(adminErrorMessage(reason, '邀请制状态加载失败'))
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void loadRequired()
    return () => { mounted.current = false }
  }, [loadRequired])

  const request = useCallback(async (params: { current?: number; pageSize?: number }) => {
    setError('')
    try {
      const result = await listInviteCodes(params.current ?? 1, params.pageSize ?? 20)
      return { data: result.list, total: result.total, success: true }
    } catch (reason) {
      if (mounted.current) setError(adminErrorMessage(reason, '邀请码加载失败'))
      return { data: [], total: 0, success: false }
    }
  }, [])

  async function changeRequired(next: boolean) {
    const previous = required
    setRequired(next)
    try {
      await setInviteRequired(next)
      messageApi.success(next ? '已开启邀请制' : '已关闭邀请制，任何手机号都可以注册')
    } catch (reason) {
      if (!mounted.current) return
      setRequired(previous)
      messageApi.error(adminErrorMessage(reason, '邀请制状态更新失败'))
    }
  }

  async function changeEnabled(row: InviteCode, enabled: boolean) {
    setBusyId(row.id)
    try {
      await setInviteCodeEnabled(row.id, enabled)
      await actionRef.current?.reload()
    } catch (reason) {
      if (mounted.current) messageApi.error(adminErrorMessage(reason, '邀请码状态更新失败'))
    } finally {
      if (mounted.current) setBusyId(null)
    }
  }

  function openCreate() {
    setCreateError('')
    setCreated([])
    form.setFieldsValue({ count: 1, maxUses: 1, expireDate: null, remark: '' })
    setCreateOpen(true)
  }

  async function create() {
    let values: CreateValues
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    setSaving(true)
    setCreateError('')
    try {
      const result = await createInviteCodes({
        count: values.count,
        maxUses: values.maxUses,
        expireDate: values.expireDate ? values.expireDate.toISOString() : null,
        remark: values.remark?.trim() || null,
      })
      if (!mounted.current) return
      setCreated(result)
      await actionRef.current?.reload()
    } catch (reason) {
      if (mounted.current) setCreateError(adminErrorMessage(reason, '邀请码生成失败'))
    } finally {
      if (mounted.current) setSaving(false)
    }
  }

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      messageApi.success('已复制')
    } catch {
      messageApi.error('复制失败，请手动选择后复制')
    }
  }

  const columns: ProColumns<InviteCode>[] = [
    {
      title: '邀请码',
      dataIndex: 'code',
      render: (_, row) => <Space size={8}>
        <Typography.Text code>{row.code}</Typography.Text>
        <Button type="link" size="small" aria-label={`复制邀请码 ${row.code}`} onClick={() => void copy(row.code)}>复制</Button>
      </Space>,
    },
    { title: '使用情况', width: 110, render: (_, row) => `${row.usedCount} / ${row.maxUses}` },
    { title: '状态', width: 100, render: (_, row) => statusTag(row) },
    { title: '失效时间', width: 190, render: (_, row) => row.expireDate ? new Date(row.expireDate).toLocaleString() : '不过期' },
    { title: '备注', dataIndex: 'remark', render: (_, row) => row.remark || '-' },
    {
      title: '启用',
      width: 80,
      render: (_, row) => <Switch
        aria-label={`启用邀请码 ${row.code}`}
        checked={row.enabled}
        loading={busyId === row.id}
        onChange={(checked) => void changeEnabled(row, checked)}
      />,
    },
  ]

  return <AdminPage
    title="邀请码"
    subTitle="内测期间控制谁可以注册"
    error={error}
    onRetry={error ? () => { void loadRequired(); void actionRef.current?.reload() } : undefined}
    actions={<Button type="primary" onClick={openCreate}>生成邀请码</Button>}
  >
    {messageContext}
    <Alert
      type={required === false ? 'warning' : 'info'}
      showIcon
      style={{ marginBottom: 16 }}
      message={required === false
        ? '邀请制已关闭：任何手机号都可以直接注册。'
        : '邀请制已开启：新手机号必须填写邀请码才能注册，已有账号登录不受影响。'}
      action={<Switch
        aria-label="开启邀请制"
        checked={required !== false}
        disabled={required === null}
        onChange={(checked) => void changeRequired(checked)}
      />}
    />
    <ProTable<InviteCode>
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      request={request}
      search={false}
      options={false}
      pagination={{ defaultPageSize: 20, showSizeChanger: false }}
      scroll={{ x: 820 }}
    />
    <Modal
      title="生成邀请码"
      open={createOpen}
      destroyOnHidden
      onCancel={() => { if (!saving) setCreateOpen(false) }}
      footer={created.length
        ? <Space>
          <Button onClick={() => void copy(created.map((item) => item.code).join('\n'))}>复制全部</Button>
          <Button type="primary" onClick={() => setCreateOpen(false)}>完成</Button>
        </Space>
        : <Space>
          <Button disabled={saving} onClick={() => setCreateOpen(false)}>取消</Button>
          <Button type="primary" loading={saving} onClick={() => void create()}>生成</Button>
        </Space>}
    >
      {createError && <Alert type="error" showIcon message={createError} style={{ marginBottom: 16 }} />}
      {created.length
        ? <>
          <Typography.Paragraph type="secondary">已生成 {created.length} 个邀请码：</Typography.Paragraph>
          <pre className="invite-code-list" aria-label="生成的邀请码">{created.map((item) => item.code).join('\n')}</pre>
        </>
        : <Form form={form} layout="vertical" requiredMark={false}>
          <Space size={16} wrap>
            <Form.Item name="count" label="生成几个" rules={[{ required: true, message: '请填写数量' }]}>
              <InputNumber aria-label="生成几个" min={1} max={100} precision={0} />
            </Form.Item>
            <Form.Item name="maxUses" label="每个可用几次" rules={[{ required: true, message: '请填写次数' }]}>
              <InputNumber aria-label="每个可用几次" min={1} max={100000} precision={0} />
            </Form.Item>
          </Space>
          <Form.Item name="expireDate" label="失效时间" extra="不填表示不过期">
            <DatePicker showTime style={{ width: '100%' }} disabledDate={(date) => date.isBefore(new Date(), 'day')} />
          </Form.Item>
          <Form.Item name="remark" label="备注" extra="例如发给了谁、哪一批内测用户" rules={[{ max: 255, message: '不能超过 255 个字' }]}>
            <Input aria-label="备注" />
          </Form.Item>
        </Form>}
    </Modal>
  </AdminPage>
}
