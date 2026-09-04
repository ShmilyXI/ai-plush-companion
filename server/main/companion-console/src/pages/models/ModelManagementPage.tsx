import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components'
import {
  Alert,
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Spin,
  Switch,
  Tabs,
  Tag,
  message,
} from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import {
  createModelConfig,
  deleteModelConfig,
  getModelConfig,
  listModelConfigs,
  listProviderTypes,
  modelTypes,
  setDefaultModel,
  setModelEnabled,
  testModelConfig,
  updateModelConfig,
  type CreateModelConfigInput,
  type ModelConfig,
  type ModelProvider,
  type ModelProviderField,
  type ModelTestResult,
  type ModelType,
  type UpdateModelConfigInput,
} from '../../api/zixuanModels'
import { ModelFieldEditor } from './ModelFieldEditor'
import { credentialStatus, isCredentialField } from './modelCredentials'
import { canTestModelConnection, llmFieldDefault } from './modelEditorMetadata'

const PAGE_SIZE = 10
const typeLabels: Record<ModelType, string> = {
  LLM: '对话模型 LLM',
  VLLM: '视觉模型 VLLM',
  TTS: '语音合成 TTS',
  ASR: '语音识别 ASR',
  VAD: '语音活动检测 VAD',
  Memory: '记忆模型 Memory',
  Embedding: 'Embedding 模型 Embedding',
}
const credentialTags = {
  configured: { color: 'success', text: '已配置' },
  missing: { color: 'error', text: '未配置' },
  not_required: { color: 'default', text: '无需配置' },
} as const

interface EditorValues {
  providerCode: string
  id?: string
  modelCode?: string
  modelName: string
  isEnabled: boolean
  docLink?: string
  remark?: string
  sort?: number
  configJson?: Record<string, NonNullable<unknown> | undefined>
}

interface ModelTableView {
  revision: number
  modelType: ModelType
  current: number
  pageSize: number
  modelName: string
  rowCount: number
}

function errorText(reason: unknown, fallback: string) {
  const value = reason instanceof Error ? reason.message.trim() : ''
  return value && value.length <= 160 ? value : fallback
}

function providerCode(model: ModelConfig) {
  const value = model.configJson?.type
  return typeof value === 'string' ? value : ''
}

function fieldInitialValue(field: ModelProviderField, value: unknown, modelType: ModelType) {
  if (isCredentialField(field)) return undefined
  if (field.type === 'dict' && value !== undefined && value !== null) return JSON.stringify(value, null, 2)
  return value ?? field.default ?? llmFieldDefault(modelType, field.key)
}

function initialConfig(provider: ModelProvider, model?: ModelConfig) {
  const values: Record<string, NonNullable<unknown> | undefined> = {}
  for (const field of provider.fields) {
    const value = model?.configJson?.[field.key]
    const initial = fieldInitialValue(field, value, provider.modelType)
    if (initial !== undefined) values[field.key] = initial
  }
  return values
}

function payloadConfig(provider: ModelProvider, values: Record<string, unknown> | undefined) {
  const config: Record<string, unknown> = { type: provider.providerCode }
  for (const field of provider.fields) {
    const value = values?.[field.key]
    if (isCredentialField(field) && (value === undefined || value === '')) continue
    if (value === undefined) continue
    if (field.type === 'dict' && typeof value === 'string') {
      if (value.trim()) config[field.key] = JSON.parse(value) as Record<string, unknown>
      continue
    }
    config[field.key] = value
  }
  return config
}

export function ModelManagementPage() {
  const [activeType, setActiveType] = useState<ModelType>('LLM')
  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<ModelConfig | null>(null)
  const [providers, setProviders] = useState<ModelProvider[]>([])
  const [tableProviders, setTableProviders] = useState<ModelProvider[]>([])
  const [editorLoading, setEditorLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<ModelTestResult | null>(null)
  const [error, setError] = useState('')
  const [editorError, setEditorError] = useState('')
  const [form] = Form.useForm<EditorValues>()
  const selectedProviderCode = Form.useWatch('providerCode', form)
  const [messageApi, messageContext] = message.useMessage()
  const actionRef = useRef<ActionType>(null)
  const mounted = useRef(false)
  const requestSequence = useRef(0)
  const tableView = useRef<ModelTableView>({
    revision: 0,
    modelType: activeType,
    current: 1,
    pageSize: PAGE_SIZE,
    modelName: '',
    rowCount: 0,
  })
  const editorController = useRef<AbortController | null>(null)
  const providerCache = useRef(new Map<ModelType, Promise<ModelProvider[]>>())
  const testController = useRef<AbortController | null>(null)
  const editorSession = useRef(0)
  const testSequence = useRef(0)
  const activeTypeRef = useRef(activeType)
  activeTypeRef.current = activeType

  function invalidateTableView(values: Partial<Omit<ModelTableView, 'revision' | 'rowCount'>> = {}) {
    requestSequence.current += 1
    tableView.current = {
      ...tableView.current,
      ...values,
      revision: tableView.current.revision + 1,
    }
  }

  function isCurrentTableView(revision: number) {
    return mounted.current && tableView.current.revision === revision
  }

  const requestModels = useCallback(async (params: {
    current?: number
    pageSize?: number
    modelName?: string
    modelType?: ModelType
  }) => {
    const sequence = ++requestSequence.current
    const modelType = params.modelType ?? 'LLM'
    const current = params.current ?? 1
    const pageSize = params.pageSize ?? PAGE_SIZE
    const modelName = typeof params.modelName === 'string' ? params.modelName.trim() : ''
    const revision = tableView.current.revision + 1
    tableView.current = { revision, modelType, current, pageSize, modelName, rowCount: 0 }
    setError('')
    try {
      let providerRequest = providerCache.current.get(modelType)
      if (!providerRequest) {
        providerRequest = listProviderTypes(modelType).catch((reason) => {
          providerCache.current.delete(modelType)
          throw reason
        })
        providerCache.current.set(modelType, providerRequest)
      }
      const [result, providerList] = await Promise.all([
        listModelConfigs({
          modelType,
          modelName,
          page: current,
          limit: pageSize,
        }),
        providerRequest,
      ])
      if (!mounted.current || requestSequence.current !== sequence || tableView.current.revision !== revision) {
        return { data: [], total: 0, success: false }
      }
      setTableProviders(providerList)
      tableView.current = { ...tableView.current, rowCount: result.list.length }
      return { data: result.list, total: result.total, success: true }
    } catch (reason) {
      if (mounted.current && requestSequence.current === sequence) {
        setError(errorText(reason, '模型列表加载失败'))
      }
      return { data: [], total: 0, success: false }
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      requestSequence.current += 1
      editorController.current?.abort()
      testController.current?.abort()
    }
  }, [])

  const selectedProvider = useMemo(
    () => providers.find((provider) => provider.providerCode === selectedProviderCode),
    [providers, selectedProviderCode],
  )
  const tableProviderMap = useMemo(
    () => new Map(tableProviders.map((provider) => [provider.providerCode, provider])),
    [tableProviders],
  )
  const connectionTestSupported = canTestModelConnection(activeType, selectedProvider?.providerCode)

  async function loadEditor(model: ModelConfig | null) {
    const session = ++editorSession.current
    editorController.current?.abort()
    const nextController = new AbortController()
    editorController.current = nextController
    testSequence.current += 1
    testController.current?.abort()
    testController.current = null
    setEditing(model)
    setEditorOpen(true)
    setProviders([])
    setEditorLoading(true)
    setSaving(false)
    setTesting(false)
    setTestResult(null)
    setError('')
    setEditorError('')
    try {
      const [providerList, details] = await Promise.all([
        listProviderTypes(activeType, { signal: nextController.signal }),
        model ? getModelConfig(model.id, { signal: nextController.signal }) : Promise.resolve(null),
      ])
      if (nextController.signal.aborted || editorSession.current !== session) return
      const code = details ? providerCode(details) : providerList[0]?.providerCode ?? ''
      const provider = providerList.find((item) => item.providerCode === code)
      setProviders(providerList)
      setEditing(details)
      form.setFieldsValue({
        providerCode: code,
        id: details?.id ?? '',
        modelCode: details?.modelCode ?? '',
        modelName: details?.modelName ?? '',
        isEnabled: details ? details.isEnabled === 1 : true,
        docLink: details?.docLink ?? '',
        remark: details?.remark ?? '',
        sort: details?.sort ?? 1,
        configJson: provider ? initialConfig(provider, details ?? undefined) : {},
      })
    } catch (reason) {
      if (!nextController.signal.aborted && editorSession.current === session) {
        setError(errorText(reason, '模型编辑器加载失败'))
        setEditorOpen(false)
        setEditing(null)
      }
    } finally {
      if (!nextController.signal.aborted && editorSession.current === session) setEditorLoading(false)
    }
  }

  function closeEditor() {
    editorSession.current += 1
    editorController.current?.abort()
    testSequence.current += 1
    testController.current?.abort()
    testController.current = null
    setEditorOpen(false)
    setEditing(null)
    setEditorLoading(false)
    setSaving(false)
    setTesting(false)
    setTestResult(null)
    setEditorError('')
    form.resetFields()
  }

  function changeProvider(code: string) {
    invalidateTestResult()
    const provider = providers.find((item) => item.providerCode === code)
    form.setFieldValue('configJson', provider ? initialConfig(provider) : {})
  }

  function invalidateTestResult() {
    testSequence.current += 1
    testController.current?.abort()
    testController.current = null
    setTesting(false)
    setTestResult(null)
  }

  async function testConnection() {
    const session = editorSession.current
    const sequence = ++testSequence.current
    const editingId = editing?.id ?? null
    testController.current?.abort()
    const nextController = new AbortController()
    testController.current = nextController
    setTesting(true)
    setTestResult(null)
    try {
      const code = form.getFieldValue('providerCode') as string | undefined
      const provider = providers.find((item) => item.providerCode === code)
      if (!provider) return
      await form.validateFields([
        ['providerCode'],
        ...provider.fields.map((field) => ['configJson', field.key]),
      ])
      const values = form.getFieldsValue(true)
      const result = await testModelConfig(
        activeTypeRef.current,
        provider.providerCode,
        editingId,
        { configJson: payloadConfig(provider, values.configJson) },
        { signal: nextController.signal },
      )
      if (mounted.current && editorSession.current === session && testSequence.current === sequence
        && !nextController.signal.aborted) {
        setTestResult(result)
      }
    } catch (reason) {
      if (mounted.current && editorSession.current === session && testSequence.current === sequence
        && !nextController.signal.aborted) {
        setTestResult({ success: false, elapsedMillis: 0, message: errorText(reason, '连接测试失败') })
      }
    } finally {
      if (mounted.current && editorSession.current === session && testSequence.current === sequence
        && !nextController.signal.aborted) {
        setTesting(false)
      }
    }
  }

  async function save() {
    const session = editorSession.current
    const saveType = activeTypeRef.current
    const editingModel = editing
    const values = await form.validateFields()
    if (editorSession.current !== session) return
    const provider = providers.find((item) => item.providerCode === values.providerCode)
    if (!provider) return
    const viewRevision = tableView.current.revision
    const configTouched = provider.fields.some((field) => form.isFieldTouched(['configJson', field.key]))
    setSaving(true)
    setEditorError('')
    try {
      const configJson = !editingModel || configTouched ? payloadConfig(provider, values.configJson) : undefined
      if (editingModel) {
        const input: UpdateModelConfigInput = {
          modelName: values.modelName,
          isEnabled: values.isEnabled ? 1 : 0,
          remark: values.remark || null,
          sort: values.sort ?? 0,
        }
        if (configJson !== undefined) input.configJson = configJson
        await updateModelConfig(saveType, provider.providerCode, editingModel.id, input)
      } else {
        const input: CreateModelConfigInput = {
          id: values.id || undefined,
          modelCode: values.modelCode || undefined,
          modelName: values.modelName,
          isEnabled: values.isEnabled ? 1 : 0,
          configJson,
          docLink: values.docLink || null,
          remark: values.remark || null,
          sort: values.sort ?? 0,
        }
        await createModelConfig(saveType, provider.providerCode, input)
      }
      if (!mounted.current || editorSession.current !== session) return
      messageApi.success(editingModel ? '模型已更新' : '模型已创建')
      closeEditor()
      if (isCurrentTableView(viewRevision)) await actionRef.current?.reload()
    } catch (reason) {
      if (mounted.current && editorSession.current === session) {
        setEditorError(errorText(reason, '模型保存失败'))
      }
    } finally {
      if (mounted.current && editorSession.current === session) setSaving(false)
    }
  }

  async function changeEnabled(row: ModelConfig, enabled: boolean) {
    const viewRevision = tableView.current.revision
    setError('')
    try {
      await setModelEnabled(row.id, enabled)
      if (isCurrentTableView(viewRevision)) await actionRef.current?.reload()
    } catch (reason) {
      if (isCurrentTableView(viewRevision)) {
        setError(errorText(reason, '模型状态更新失败'))
      }
    }
  }

  async function makeDefault(row: ModelConfig) {
    const viewRevision = tableView.current.revision
    setError('')
    try {
      await setDefaultModel(row.id)
      if (isCurrentTableView(viewRevision)) await actionRef.current?.reload()
    } catch (reason) {
      if (isCurrentTableView(viewRevision)) {
        setError(errorText(reason, '默认模型更新失败'))
      }
    }
  }

  async function remove(row: ModelConfig) {
    const { revision: viewRevision, current, rowCount } = tableView.current
    setError('')
    try {
      await deleteModelConfig(row.id)
      messageApi.success('模型已删除')
      if (isCurrentTableView(viewRevision)) {
        if (rowCount === 1 && current > 1) {
          actionRef.current?.setPageInfo?.({ current: current - 1 })
        }
        await actionRef.current?.reload()
      }
    } catch (reason) {
      if (isCurrentTableView(viewRevision)) {
        setError(errorText(reason, '模型删除失败'))
      }
    }
  }

  const columns: ProColumns<ModelConfig>[] = [
    { title: '模型 ID', dataIndex: 'id', hideInSearch: true },
    { title: '模型名称', dataIndex: 'modelName' },
    { title: '模型编码', dataIndex: 'modelCode', hideInSearch: true },
    {
      title: '供应器',
      hideInSearch: true,
      render: (_, row) => providerCode(row) || '未设置',
    },
    {
      title: 'Key 状态',
      width: 110,
      hideInSearch: true,
      render: (_, row) => {
        const status = credentialStatus(row, tableProviderMap.get(providerCode(row)))
        const tag = credentialTags[status]
        return <Tag color={tag.color}>{tag.text}</Tag>
      },
    },
    {
      title: '启用',
      width: 90,
      hideInSearch: true,
      render: (_, row) => <Switch
        aria-label={`启用 ${row.modelName}`}
        checked={row.isEnabled === 1}
        disabled={row.isDefault === 1 && row.isEnabled === 1}
        onChange={(checked) => void changeEnabled(row, checked)}
      />,
    },
    {
      title: '默认',
      width: 100,
      hideInSearch: true,
      render: (_, row) => row.isDefault === 1 ? <Tag color="gold">默认</Tag> : <Popconfirm
        title="确认设为默认模型？"
        okText="确定"
        cancelText="取消"
        onConfirm={() => makeDefault(row)}
      ><Button size="small">设为默认</Button></Popconfirm>,
    },
    ...(activeType === 'TTS' ? [{
      title: '音色',
      width: 96,
      align: 'center' as const,
      hideInSearch: true,
      onHeaderCell: () => ({ style: { width: 96, minWidth: 96, maxWidth: 96 } }),
      onCell: () => ({ style: { width: 96, minWidth: 96, maxWidth: 96 } }),
      render: () => <Link
        style={{ whiteSpace: 'nowrap' }}
        to="/voices?tab=timbres"
      >管理音色</Link>,
    }] : []),
    {
      title: '操作',
      width: 150,
      hideInSearch: true,
      render: (_, row) => <Space>
        <Button type="text" icon={<EditOutlined />} aria-label={`编辑 ${row.modelName}`} onClick={() => void loadEditor(row)}>编辑</Button>
        <Popconfirm title="确认删除这个模型？" onConfirm={() => remove(row)} disabled={row.isDefault === 1}>
          <Button type="text" danger disabled={row.isDefault === 1} icon={<DeleteOutlined />} aria-label={`删除 ${row.modelName}`}>删除</Button>
        </Popconfirm>
      </Space>,
    },
  ]
  const testButtonText = testing ? '测试中'
    : testResult?.success ? `测试成功 · ${testResult.elapsedMillis} ms`
      : testResult ? '测试失败' : '测试连接'
  const testButtonTitle = testResult && !testResult.success ? testResult.message : undefined

  return <PageContainer
    className="model-management-page"
    title={<h1 className="page-container-title">模型管理</h1>}
    subTitle="维护语音识别、对话、视觉、合成和记忆模型"
  >
    {messageContext}
    {error && <Alert type="error" showIcon message={error} action={<Button size="small" onClick={() => void actionRef.current?.reload()}>重试</Button>} />}
    <Tabs
      activeKey={activeType}
      onChange={(value) => {
        invalidateTableView({ modelType: value as ModelType, current: 1 })
        actionRef.current?.setPageInfo?.({ current: 1 })
        setActiveType(value as ModelType)
      }}
      items={modelTypes.map((type) => ({ key: type, label: typeLabels[type] }))}
    />
    <ProTable<ModelConfig>
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      params={{ modelType: activeType }}
      request={requestModels}
      search={{ labelWidth: 'auto' }}
      onSubmit={(values) => invalidateTableView({
        current: 1,
        modelName: typeof values.modelName === 'string' ? values.modelName.trim() : '',
      })}
      onReset={() => invalidateTableView({ current: 1, modelName: '' })}
      onChange={(pagination) => {
        const current = pagination.current ?? 1
        const pageSize = pagination.pageSize ?? PAGE_SIZE
        if (current !== tableView.current.current || pageSize !== tableView.current.pageSize) {
          invalidateTableView({ current, pageSize })
        }
      }}
      pagination={{
        defaultPageSize: PAGE_SIZE,
        showSizeChanger: false,
      }}
      toolBarRender={() => [<Button key="create" aria-label="新增模型" type="primary" icon={<PlusOutlined />} onClick={() => void loadEditor(null)}>新增模型</Button>]}
      locale={{ emptyText: <Empty description="这个类别还没有模型" /> }}
      scroll={{ x: activeType === 'TTS' ? 1160 : 1050 }}
    />
    <Drawer
      title={editing ? '编辑模型' : '新增模型'}
      aria-label={editing ? '编辑模型' : '新增模型'}
      open={editorOpen}
      width={560}
      destroyOnHidden
      onClose={closeEditor}
      footer={<Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
        {connectionTestSupported && <Button
          aria-label={testButtonText}
          danger={testResult?.success === false}
          title={testButtonTitle}
          loading={testing}
          disabled={editorLoading || saving || !selectedProvider}
          onClick={() => void testConnection()}
        >{testButtonText}</Button>}
        <Button onClick={closeEditor}>取消</Button>
        <Button type="primary" loading={saving} disabled={editorLoading || !selectedProvider} onClick={() => void save()}>保存</Button>
      </Space>}
    >
      <Spin spinning={editorLoading}>
        {editorError && <Alert type="error" showIcon message={editorError} />}
        <Form form={form} layout="vertical" requiredMark={false} onValuesChange={invalidateTestResult}>
          <Form.Item name="providerCode" label="供应器" rules={[{ required: true, message: '请选择供应器' }]}>
            <Select
              aria-label="供应器"
              disabled={Boolean(editing)}
              options={providers.map((provider) => ({ value: provider.providerCode, label: provider.name }))}
              onChange={changeProvider}
            />
          </Form.Item>
          <Form.Item name="id" label="模型 ID">
            <Input aria-label="模型 ID" disabled={Boolean(editing)} maxLength={64} placeholder="留空时由服务端生成" />
          </Form.Item>
          <Form.Item name="modelCode" label="模型编码" rules={[{ required: !editing, message: '请输入模型编码' }]}>
            <Input aria-label="模型编码" disabled={Boolean(editing)} />
          </Form.Item>
          <Form.Item name="modelName" label="模型名称" rules={[{ required: true, message: '请输入模型名称' }]}>
            <Input aria-label="模型名称" />
          </Form.Item>
          <Form.Item name="isEnabled" label="启用" valuePropName="checked">
            <Switch aria-label="启用" />
          </Form.Item>
          <Form.Item name="sort" label="排序">
            <InputNumber aria-label="排序" min={0} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="docLink" label="文档地址">
            <Input aria-label="文档地址" disabled={Boolean(editing)} />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea aria-label="备注" rows={3} />
          </Form.Item>
          {selectedProvider && <ModelFieldEditor
            modelType={activeType}
            fields={selectedProvider.fields}
            configuredSecretPaths={new Set(editing?.configuredSecretPaths ?? [])}
          />}
        </Form>
      </Spin>
    </Drawer>
  </PageContainer>
}
