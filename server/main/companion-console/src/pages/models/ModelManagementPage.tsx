import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
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
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
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
} from '../../api/xiaozhiModels'
import { ModelFieldEditor } from './ModelFieldEditor'

const PAGE_SIZE = 10
const typeLabels: Record<ModelType, string> = {
  LLM: '对话模型 LLM',
  VLLM: '视觉模型 VLLM',
  TTS: '语音合成 TTS',
  ASR: '语音识别 ASR',
  VAD: '语音活动检测 VAD',
  Memory: '记忆模型 Memory',
}

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

function errorText(reason: unknown, fallback: string) {
  const value = reason instanceof Error ? reason.message.trim() : ''
  return value && value.length <= 160 ? value : fallback
}

function providerCode(model: ModelConfig) {
  const value = model.configJson?.type
  return typeof value === 'string' ? value : ''
}

function isCredentialKey(key: string) {
  const normalized = key.replace(/([a-z0-9])([A-Z])/g, '$1_$2').replace(/[^a-zA-Z0-9]+/g, '_').toLowerCase()
  return /(^|_)(token|secret|password|authorization|credential)($|_)/.test(normalized)
    || normalized.includes('api_key')
    || normalized.includes('access_key_secret')
    || normalized.includes('private_key')
}

function fieldInitialValue(field: ModelProviderField, value: unknown) {
  if (field.type === 'password' || isCredentialKey(field.key)) return undefined
  if (field.type === 'dict' && value !== undefined && value !== null) return JSON.stringify(value, null, 2)
  return value ?? field.default ?? undefined
}

function initialConfig(provider: ModelProvider, model?: ModelConfig) {
  const values: Record<string, NonNullable<unknown> | undefined> = {}
  for (const field of provider.fields) {
    const value = model?.configJson?.[field.key]
    const initial = fieldInitialValue(field, value)
    if (initial !== undefined) values[field.key] = initial
  }
  return values
}

function payloadConfig(provider: ModelProvider, values: Record<string, unknown> | undefined) {
  const config: Record<string, unknown> = { type: provider.providerCode }
  for (const field of provider.fields) {
    const value = values?.[field.key]
    if ((field.type === 'password' || isCredentialKey(field.key)) && (value === undefined || value === '')) continue
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
  const [page, setPage] = useState(1)
  const [models, setModels] = useState<ModelConfig[]>([])
  const [total, setTotal] = useState(0)
  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<ModelConfig | null>(null)
  const [providers, setProviders] = useState<ModelProvider[]>([])
  const [editorLoading, setEditorLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<ModelTestResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [editorError, setEditorError] = useState('')
  const [form] = Form.useForm<EditorValues>()
  const selectedProviderCode = Form.useWatch('providerCode', form)
  const [messageApi, messageContext] = message.useMessage()
  const mounted = useRef(false)
  const request = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const editorController = useRef<AbortController | null>(null)
  const testController = useRef<AbortController | null>(null)
  const editorSession = useRef(0)
  const testSequence = useRef(0)
  const activeTypeRef = useRef(activeType)
  const pageRef = useRef(page)
  activeTypeRef.current = activeType
  pageRef.current = page

  const load = useCallback(async (type: ModelType, nextPage: number) => {
    const sequence = ++request.current
    controller.current?.abort()
    const nextController = new AbortController()
    controller.current = nextController
    setLoading(true)
    setError('')
    try {
      const result = await listModelConfigs(
        { modelType: type, page: nextPage, limit: PAGE_SIZE },
        { signal: nextController.signal },
      )
      if (mounted.current && request.current === sequence && !nextController.signal.aborted) {
        setModels(result.list)
        setTotal(result.total)
      }
    } catch (reason) {
      if (mounted.current && request.current === sequence && !nextController.signal.aborted) {
        setError(errorText(reason, '模型列表加载失败'))
      }
    } finally {
      if (mounted.current && request.current === sequence && !nextController.signal.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void load(activeType, page)
    return () => {
      mounted.current = false
      request.current += 1
      controller.current?.abort()
      editorController.current?.abort()
      testController.current?.abort()
    }
  }, [activeType, load, page])

  const selectedProvider = useMemo(
    () => providers.find((provider) => provider.providerCode === selectedProviderCode),
    [providers, selectedProviderCode],
  )

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
    const savePage = pageRef.current
    const editingModel = editing
    const values = await form.validateFields()
    if (editorSession.current !== session) return
    const provider = providers.find((item) => item.providerCode === values.providerCode)
    if (!provider) return
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
      if (activeTypeRef.current === saveType && pageRef.current === savePage) {
        await load(saveType, savePage)
      }
    } catch (reason) {
      if (mounted.current && editorSession.current === session) {
        setEditorError(errorText(reason, '模型保存失败'))
      }
    } finally {
      if (mounted.current && editorSession.current === session) setSaving(false)
    }
  }

  async function changeEnabled(row: ModelConfig, enabled: boolean) {
    const mutationType = activeTypeRef.current
    const mutationPage = pageRef.current
    setError('')
    try {
      await setModelEnabled(row.id, enabled)
      if (activeTypeRef.current === mutationType && pageRef.current === mutationPage) {
        await load(mutationType, mutationPage)
      }
    } catch (reason) {
      if (activeTypeRef.current === mutationType && pageRef.current === mutationPage) {
        setError(errorText(reason, '模型状态更新失败'))
      }
    }
  }

  async function makeDefault(row: ModelConfig) {
    const mutationType = activeTypeRef.current
    const mutationPage = pageRef.current
    setError('')
    try {
      await setDefaultModel(row.id)
      if (activeTypeRef.current === mutationType && pageRef.current === mutationPage) {
        await load(mutationType, mutationPage)
      }
    } catch (reason) {
      if (activeTypeRef.current === mutationType && pageRef.current === mutationPage) {
        setError(errorText(reason, '默认模型更新失败'))
      }
    }
  }

  async function remove(row: ModelConfig) {
    const mutationType = activeTypeRef.current
    const mutationPage = pageRef.current
    const shouldMoveToPreviousPage = models.length === 1 && mutationPage > 1
    setError('')
    try {
      await deleteModelConfig(row.id)
      messageApi.success('模型已删除')
      if (activeTypeRef.current === mutationType && pageRef.current === mutationPage) {
        if (shouldMoveToPreviousPage) setPage(mutationPage - 1)
        else await load(mutationType, mutationPage)
      }
    } catch (reason) {
      if (activeTypeRef.current === mutationType && pageRef.current === mutationPage) {
        setError(errorText(reason, '模型删除失败'))
      }
    }
  }

  const columns: ColumnsType<ModelConfig> = [
    { title: '模型 ID', dataIndex: 'id' },
    { title: '模型名称', dataIndex: 'modelName' },
    { title: '模型编码', dataIndex: 'modelCode' },
    {
      title: '供应器',
      render: (_, row) => providerCode(row) || '未设置',
    },
    {
      title: '启用',
      width: 90,
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
      render: (_, row) => row.isDefault === 1 ? <Tag color="gold">默认</Tag> : <Popconfirm
        title="确认设为默认模型？"
        okText="确定"
        cancelText="取消"
        onConfirm={() => void makeDefault(row)}
      ><Button size="small">设为默认</Button></Popconfirm>,
    },
    ...(activeType === 'TTS' ? [{
      title: '音色',
      width: 110,
      render: (_: unknown, row: ModelConfig) => <Link to={`/admin/voices?ttsModelId=${encodeURIComponent(row.id)}`}>管理音色</Link>,
    }] : []),
    {
      title: '操作',
      width: 150,
      render: (_, row) => <Space>
        <Button type="text" icon={<EditOutlined />} aria-label={`编辑 ${row.modelName}`} onClick={() => void loadEditor(row)}>编辑</Button>
        <Popconfirm title="确认删除这个模型？" onConfirm={() => void remove(row)} disabled={row.isDefault === 1}>
          <Button type="text" danger disabled={row.isDefault === 1} icon={<DeleteOutlined />} aria-label={`删除 ${row.modelName}`}>删除</Button>
        </Popconfirm>
      </Space>,
    },
  ]

  return <section className="console-page model-management-page">
    {messageContext}
    <div className="page-heading">
      <div>
        <Typography.Title level={1}>模型管理</Typography.Title>
        <Typography.Paragraph>管理本地小智服务使用的模型供应器和配置。</Typography.Paragraph>
      </div>
      <Button aria-label="新增模型" type="primary" icon={<PlusOutlined />} onClick={() => void loadEditor(null)}>新增模型</Button>
    </div>
    {error && <Alert type="error" showIcon message={error} action={<Button size="small" onClick={() => void load(activeType, page)}>重试</Button>} />}
    <Card className="surface-card" styles={{ body: { paddingTop: 8 } }}>
      <Tabs
        activeKey={activeType}
        onChange={(value) => {
          setActiveType(value as ModelType)
          setPage(1)
        }}
        items={modelTypes.map((type) => ({ key: type, label: typeLabels[type] }))}
      />
      <Spin spinning={loading}>
        <div className="safe-table-scroll">
          <Table
            rowKey="id"
            columns={columns}
            dataSource={models}
            locale={{ emptyText: <Empty description="这个类别还没有模型" /> }}
            pagination={{
              current: page,
              pageSize: PAGE_SIZE,
              total,
              showSizeChanger: false,
              onChange: setPage,
            }}
            scroll={{ x: activeType === 'TTS' ? 1050 : 940 }}
          />
        </div>
      </Spin>
    </Card>
    <Drawer
      title={editing ? '编辑模型' : '新增模型'}
      aria-label={editing ? '编辑模型' : '新增模型'}
      open={editorOpen}
      width={560}
      destroyOnHidden
      onClose={closeEditor}
      footer={<Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button loading={testing} disabled={editorLoading || saving || !selectedProvider} onClick={() => void testConnection()}>测试连接</Button>
        <Button onClick={closeEditor}>取消</Button>
        <Button type="primary" loading={saving} disabled={editorLoading || !selectedProvider} onClick={() => void save()}>保存</Button>
      </Space>}
    >
      <Spin spinning={editorLoading}>
        {editorError && <Alert type="error" showIcon message={editorError} />}
        {testResult && <Alert
          type={testResult.success ? 'success' : 'error'}
          showIcon
          message={`${testResult.message} · ${testResult.elapsedMillis} ms`}
        />}
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
            fields={selectedProvider.fields}
            configuredSecretPaths={new Set(editing?.configuredSecretPaths ?? [])}
          />}
        </Form>
      </Spin>
    </Drawer>
  </section>
}
