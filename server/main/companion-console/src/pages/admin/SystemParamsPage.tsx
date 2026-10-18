import { EyeInvisibleOutlined, EyeOutlined } from '@ant-design/icons'
import { ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components'
import { Button, Form, Input, Modal, Typography } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { listSystemParams, updateSystemParam, type SystemParam } from '../../api/admin'
import { AdminPage } from './AdminPage'

const SENSITIVE_HINT = '敏感参数（密钥/凭证类）请确认环境安全后再显示或修改'

function maskValue(value: string) {
  if (!value) return '（未配置）'
  if (value.length <= 6) return '••••••'
  return `${value.slice(0, 4)}••••${value.slice(-2)}`
}

export function SystemParamsPage() {
  const [error, setError] = useState('')
  const [revealed, setRevealed] = useState<Record<string, boolean>>({})
  const [editing, setEditing] = useState<SystemParam | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<Pick<SystemParam, 'paramValue' | 'remark'>>()
  const actionRef = useRef<ActionType>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const sequence = useRef(0)
  const mounted = useRef(false)

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; sequence.current += 1; controllerRef.current?.abort() }
  }, [])

  const request = useCallback(async (params: { current?: number; pageSize?: number; paramCode?: string }) => {
    const requestId = ++sequence.current
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    setError('')
    try {
      const result = await listSystemParams(params.paramCode?.trim() || '', params.current ?? 1, params.pageSize ?? 20, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || sequence.current !== requestId) return { data: [], total: 0, success: false }
      return { data: result.list, total: result.total, success: true }
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && sequence.current === requestId) setError(reason instanceof Error ? reason.message : '系统参数加载失败')
      return { data: [], total: 0, success: false }
    }
  }, [])

  const openEdit = (param: SystemParam) => {
    setEditing(param)
    form.setFieldsValue({ paramValue: param.paramValue, remark: param.remark })
  }

  const submitEdit = async () => {
    if (!editing) return
    const values = await form.validateFields()
    setSaving(true)
    try {
      await updateSystemParam({ ...editing, paramValue: values.paramValue, remark: values.remark ?? '' })
      setEditing(null)
      setError('')
      void actionRef.current?.reload()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '参数保存失败')
    } finally {
      setSaving(false)
    }
  }

  const columns: ProColumns<SystemParam>[] = [
    { title: '参数编码', dataIndex: 'paramCode', ellipsis: true },
    {
      title: '参数值',
      dataIndex: 'paramValue',
      ellipsis: true,
      hideInSearch: true,
      render: (_, row) => (
        <span className="system-param-value">
          <Typography.Text copyable={revealed[row.id] ? { text: row.paramValue } : false}>
            {revealed[row.id] ? (row.paramValue || '（未配置）') : maskValue(row.paramValue)}
          </Typography.Text>
          <Button
            type="text"
            size="small"
            aria-label={revealed[row.id] ? '隐藏参数值' : '显示参数值'}
            icon={revealed[row.id] ? <EyeInvisibleOutlined /> : <EyeOutlined />}
            onClick={() => setRevealed((prev) => ({ ...prev, [row.id]: !prev[row.id] }))}
          />
        </span>
      ),
    },
    { title: '备注', dataIndex: 'remark', ellipsis: true, hideInSearch: true },
    {
      title: '操作',
      valueType: 'option',
      hideInSearch: true,
      render: (_, row) => [<a key="edit" onClick={() => openEdit(row)}>编辑</a>],
    },
  ]

  return (
    <AdminPage title="参数管理" subTitle="平台运行参数（短信凭证、服务地址、密钥等），保存后立即生效并同步缓存" error={error} onRetry={() => void actionRef.current?.reload()}>
      <ProTable<SystemParam>
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        request={request}
        scroll={{ x: 760 }}
        pagination={{ defaultPageSize: 20 }}
        options={false}
        search={{ labelWidth: 'auto' }}
      />
      <Modal
        title={editing ? `编辑参数：${editing.paramCode}` : '编辑参数'}
        open={editing !== null}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        onOk={() => void submitEdit()}
        onCancel={() => setEditing(null)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="paramValue" label="参数值" rules={[{ required: false }]}>
            <Input.TextArea rows={3} autoComplete="off" />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input autoComplete="off" />
          </Form.Item>
          <Typography.Text type="secondary">{SENSITIVE_HINT}</Typography.Text>
        </Form>
      </Modal>
    </AdminPage>
  )
}
