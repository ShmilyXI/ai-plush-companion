import { PlusOutlined } from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Divider,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import {
  addChannelModels,
  channelModelTypes,
  createModelChannel,
  deleteModelChannel,
  listChannelModels,
  listModelChannels,
  listUpstreamModels,
  testModelChannel,
  updateChannelModel,
  updateModelChannel,
  type AddChannelModelInput,
  type ChannelModel,
  type ChannelModelParamValue,
  type ChannelModelType,
  type ChannelProviderCode,
  type ModelChannel,
  type ModelChannelInput,
  type UpstreamModel,
} from '../../api/modelChannels'
import {
  deleteModelConfig,
  listModelConfigs,
  listProviderTypes,
  setDefaultModel,
  setModelEnabled,
  type ModelProviderField,
  type ModelTestResult,
} from '../../api/zixuanModels'
import {
  channelPresets,
  channelProviderLabels,
  channelTypeLabels,
  suggestCapabilities,
  supportedModelTypes,
} from './channelPresets'
import { ModelFieldEditor } from './ModelFieldEditor'
import { isCredentialField } from './modelCredentials'

interface DefaultModel { id: string, modelName: string }
type Defaults = Record<ChannelModelType, DefaultModel | null>

/**
 * 页面上的一个模型。服务端按用途各存一条配置，这里按“连接 + 模型代号”合并成一行，
 * 让一个模型只需要导入一次，用能力勾选决定它能用在哪些用途上。
 */
interface ImportedModel {
  key: string
  channelId: string
  modelId: string
  modelName: string
  /** 每种能力对应的那条服务端配置。 */
  rows: Partial<Record<ChannelModelType, ChannelModel>>
  isEnabled: boolean
}

const emptyDefaults: Defaults = { LLM: null, VLLM: null, Embedding: null }
const defaultHelp: Record<ChannelModelType, string> = {
  LLM: '角色没有单独指定对话模型时使用。',
  VLLM: '看图、拍照识别时使用。',
  Embedding: '记忆和知识库检索时使用。',
}
/** 这些配置由连接和模型代号决定，不在参数里修改。 */
const managedFieldKeys = new Set(['type', 'base_url', 'api_key', 'model_name'])
/** 默认模型下拉里，表示“先给这个模型加上这种能力再设为默认”的选项前缀。 */
const ADD_CAPABILITY_PREFIX = 'add:'

function errorText(reason: unknown, fallback: string) {
  const value = reason instanceof Error ? reason.message.trim() : ''
  return value && value.length <= 160 ? value : fallback
}

async function loadDefaults(): Promise<Defaults> {
  const entries = await Promise.all(channelModelTypes.map(async (modelType) => {
    const page = await listModelConfigs({ modelType, page: 1, limit: 100 })
    const current = page.list.find((model) => model.isDefault === 1)
    return [modelType, current ? { id: current.id, modelName: current.modelName } : null] as const
  }))
  return Object.fromEntries(entries) as Defaults
}

function groupModels(models: ChannelModel[]): ImportedModel[] {
  const groups = new Map<string, ImportedModel>()
  for (const model of models) {
    // 没有模型代号的配置无法合并，各自成行。
    const key = model.modelId ? `${model.channelId}\n${model.modelId}` : `${model.channelId}\n\n${model.id}`
    let group = groups.get(key)
    if (!group) {
      group = { key, channelId: model.channelId, modelId: model.modelId, modelName: model.modelName, rows: {}, isEnabled: true }
      groups.set(key, group)
    }
    if (!group.rows[model.modelType]) group.rows[model.modelType] = model
  }
  for (const group of groups.values()) {
    const rows = modelRows(group)
    // 同一个模型的几条配置名称应该一致；不一致时以对话那条为准。
    group.modelName = (group.rows.LLM ?? rows[0]).modelName
    group.isEnabled = rows.every((row) => row.isEnabled)
  }
  return [...groups.values()]
}

function modelRows(model: ImportedModel) {
  return channelModelTypes.flatMap((type) => model.rows[type] ? [model.rows[type]] : [])
}

function hasDefault(model: ImportedModel) {
  return modelRows(model).some((row) => row.isDefault)
}

export function ModelChannelsPanel() {
  const [channels, setChannels] = useState<ModelChannel[]>([])
  const [models, setModels] = useState<ChannelModel[]>([])
  const [defaults, setDefaults] = useState<Defaults>(emptyDefaults)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [typeFilter, setTypeFilter] = useState<ChannelModelType | 'all'>('all')
  const [editorOpen, setEditorOpen] = useState(false)
  const [editingChannel, setEditingChannel] = useState<ModelChannel | null>(null)
  const [pickerChannel, setPickerChannel] = useState<ModelChannel | null>(null)
  const [paramsModelKey, setParamsModelKey] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [messageApi, messageContext] = message.useMessage()
  const mounted = useRef(false)
  const loadSequence = useRef(0)

  const reload = useCallback(async () => {
    const sequence = ++loadSequence.current
    setError('')
    try {
      const [channelList, modelList, defaultModels] = await Promise.all([
        listModelChannels(),
        listChannelModels(),
        loadDefaults(),
      ])
      if (!mounted.current || loadSequence.current !== sequence) return
      setChannels(channelList)
      setModels(modelList)
      setDefaults(defaultModels)
    } catch (reason) {
      if (mounted.current && loadSequence.current === sequence) setError(errorText(reason, '模型连接加载失败'))
    } finally {
      if (mounted.current && loadSequence.current === sequence) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void reload()
    return () => {
      mounted.current = false
      loadSequence.current += 1
    }
  }, [reload])

  const channelById = useMemo(() => new Map(channels.map((channel) => [channel.id, channel])), [channels])
  const imported = useMemo(() => groupModels(models), [models])
  const importedByKey = useMemo(() => new Map(imported.map((model) => [model.key, model])), [imported])
  const importedCountByChannel = useMemo(() => {
    const counts = new Map<string, number>()
    for (const model of imported) counts.set(model.channelId, (counts.get(model.channelId) ?? 0) + 1)
    return counts
  }, [imported])
  const visibleModels = useMemo(
    () => typeFilter === 'all' ? imported : imported.filter((model) => model.rows[typeFilter]),
    [imported, typeFilter],
  )
  const paramsModel = paramsModelKey ? importedByKey.get(paramsModelKey) ?? null : null

  function capabilityTypes(model: ImportedModel) {
    const channel = channelById.get(model.channelId)
    return channel ? supportedModelTypes(channel.providerCode) : channelModelTypes.filter((type) => model.rows[type])
  }

  async function run(id: string, action: () => Promise<void>, success: string, fallback: string) {
    setBusyId(id)
    try {
      await action()
      if (!mounted.current) return
      if (success) messageApi.success(success)
    } catch (reason) {
      if (mounted.current) messageApi.error(errorText(reason, fallback))
    } finally {
      // 一次操作可能改了好几条配置，无论成败都以服务端的结果为准。
      if (mounted.current) {
        await reload()
        setBusyId(null)
      }
    }
  }

  function openEditor(channel: ModelChannel | null) {
    setEditingChannel(channel)
    setEditorOpen(true)
  }

  async function channelSaved(channel: ModelChannel, created: boolean) {
    setEditorOpen(false)
    setEditingChannel(null)
    messageApi.success(created ? '连接已添加，接着选择要用的模型' : '连接已更新')
    await reload()
    // 新建连接后直接进入“拉取模型”，把渠道和模型两步连起来。
    if (created && mounted.current) setPickerChannel(channel)
  }

  function toggleCapability(model: ImportedModel, type: ChannelModelType, checked: boolean) {
    const row = model.rows[type]
    if (checked && !row) {
      return run(model.key, async () => {
        await addChannelModels(model.channelId, [{ modelType: type, modelId: model.modelId, modelName: model.modelName }])
      }, '', '能力添加失败')
    }
    if (!checked && row) {
      return run(model.key, () => deleteModelConfig(row.id), '', '能力移除失败')
    }
    return Promise.resolve()
  }

  function toggleEnabled(model: ImportedModel, enabled: boolean) {
    return run(model.key, async () => {
      for (const row of modelRows(model)) {
        if (row.isEnabled !== enabled) await setModelEnabled(row.id, enabled)
      }
    }, '', '模型状态更新失败')
  }

  function removeModel(model: ImportedModel) {
    return run(model.key, async () => {
      for (const row of modelRows(model)) await deleteModelConfig(row.id)
    }, '模型已移除', '模型移除失败')
  }

  function chooseDefault(type: ChannelModelType, value: string) {
    return run(`default-${type}`, async () => {
      if (!value.startsWith(ADD_CAPABILITY_PREFIX)) {
        await setDefaultModel(value)
        return
      }
      // 选了一个还没有这种能力的模型：先给它加上能力，再设为默认。
      const model = importedByKey.get(value.slice(ADD_CAPABILITY_PREFIX.length))
      if (!model) throw new Error('模型已不存在，请刷新后重试')
      const created = await addChannelModels(model.channelId, [{
        modelType: type,
        modelId: model.modelId,
        modelName: model.modelName,
      }])
      const row = created.find((item) => item.modelType === type)
      if (!row) throw new Error('这个模型已经有这种能力，请刷新后重试')
      await setDefaultModel(row.id)
    }, '默认模型已更新', '默认模型更新失败')
  }

  const channelColumns: ColumnsType<ModelChannel> = [
    {
      title: '连接',
      dataIndex: 'name',
      render: (_, row) => <Space size={8} wrap>
        <Typography.Text strong>{row.name}</Typography.Text>
        <Tag>{channelProviderLabels[row.providerCode]}</Tag>
      </Space>,
    },
    {
      title: '接口地址',
      dataIndex: 'baseUrl',
      render: (value: string) => <Typography.Text type="secondary" style={{ overflowWrap: 'anywhere' }}>{value}</Typography.Text>,
    },
    {
      title: '密钥',
      width: 110,
      render: (_, row) => row.providerCode === 'ollama'
        ? <Tag>无需配置</Tag>
        : row.credentialConfigured ? <Tag color="success">已配置</Tag> : <Tag color="warning">未配置</Tag>,
    },
    { title: '模型数', width: 90, render: (_, row) => importedCountByChannel.get(row.id) ?? 0 },
    {
      title: '操作',
      width: 260,
      render: (_, row) => <Space size={4} wrap>
        <Button type="link" size="small" aria-label={`拉取 ${row.name} 的模型`} onClick={() => setPickerChannel(row)}>拉取模型</Button>
        <Button type="link" size="small" aria-label={`编辑连接 ${row.name}`} onClick={() => openEditor(row)}>编辑</Button>
        <Popconfirm
          title="确认删除这个连接？"
          okText="删除"
          cancelText="取消"
          disabled={row.modelCount > 0}
          onConfirm={() => run(row.id, () => deleteModelChannel(row.id), '连接已删除', '连接删除失败')}
        >
          <Tooltip title={row.modelCount > 0 ? '请先移除这个连接下的模型' : undefined}>
            <Button
              type="link"
              size="small"
              danger
              aria-label={`删除连接 ${row.name}`}
              disabled={row.modelCount > 0}
              loading={busyId === row.id}
            >删除</Button>
          </Tooltip>
        </Popconfirm>
      </Space>,
    },
  ]

  const modelColumns: ColumnsType<ImportedModel> = [
    {
      title: '显示名称',
      dataIndex: 'modelName',
      render: (_, row) => <Space size={8} wrap>
        <span>{row.modelName}</span>
        {channelModelTypes.map((type) => row.rows[type]?.isDefault
          ? <Tag color="gold" key={type}>默认{channelTypeLabels[type]}</Tag>
          : null)}
      </Space>,
    },
    {
      title: '模型代号',
      dataIndex: 'modelId',
      render: (value: string) => <Typography.Text code>{value || '未设置'}</Typography.Text>,
    },
    { title: '连接', render: (_, row) => channelById.get(row.channelId)?.name ?? '连接已不存在' },
    {
      title: '能力',
      width: 230,
      render: (_, row) => {
        const count = modelRows(row).length
        return <Space size={12} wrap>
          {capabilityTypes(row).map((type) => {
            const current = row.rows[type]
            const locked = current
              ? current.isDefault ? `它是默认${channelTypeLabels[type]}模型，请先换一个默认模型` : count === 1 ? '至少保留一种能力' : ''
              : row.modelId ? '' : '缺少模型代号'
            return <Tooltip title={locked || undefined} key={type}>
              <Checkbox
                aria-label={`${row.modelName} 支持${channelTypeLabels[type]}`}
                checked={Boolean(current)}
                disabled={Boolean(locked) || busyId === row.key}
                onChange={(event) => void toggleCapability(row, type, event.target.checked)}
              >{channelTypeLabels[type]}</Checkbox>
            </Tooltip>
          })}
        </Space>
      },
    },
    {
      title: '启用',
      width: 80,
      render: (_, row) => <Switch
        aria-label={`启用 ${row.modelName}`}
        checked={row.isEnabled}
        disabled={hasDefault(row) && row.isEnabled}
        loading={busyId === row.key}
        onChange={(checked) => void toggleEnabled(row, checked)}
      />,
    },
    {
      title: '操作',
      width: 150,
      render: (_, row) => <Space size={4}>
        <Button type="link" size="small" aria-label={`调整 ${row.modelName} 的参数`} onClick={() => setParamsModelKey(row.key)}>参数</Button>
        <Popconfirm
          title="确认移除这个模型？"
          okText="移除"
          cancelText="取消"
          disabled={hasDefault(row)}
          onConfirm={() => removeModel(row)}
        >
          <Tooltip title={hasDefault(row) ? '默认模型不能移除，请先换一个默认模型' : undefined}>
            <Button type="link" size="small" danger aria-label={`移除 ${row.modelName}`} disabled={hasDefault(row)}>移除</Button>
          </Tooltip>
        </Popconfirm>
      </Space>,
    },
  ]

  function defaultOptions(type: ChannelModelType) {
    const current = defaults[type]
    const ready = imported.flatMap((model) => {
      const row = model.rows[type]
      return row && row.isEnabled ? [{ value: row.id, label: model.modelName }] : []
    })
    // 对话和视觉可以直接从其他已导入的模型里选，选中后自动补上对应能力。向量模型不能拿对话模型代替。
    const others = type === 'Embedding' ? [] : imported.flatMap((model) => {
      const usable = !model.rows[type] && model.isEnabled && model.modelId
        && (model.rows.LLM || model.rows.VLLM) && capabilityTypes(model).includes(type)
      return usable ? [{ value: `${ADD_CAPABILITY_PREFIX}${model.key}`, label: model.modelName }] : []
    })
    const standalone = current && !ready.some((option) => option.value === current.id)
      ? [{ value: current.id, label: `${current.modelName}（单独配置）` }]
      : []
    const groups = []
    if (standalone.length + ready.length) groups.push({ label: `支持${channelTypeLabels[type]}的模型`, options: [...standalone, ...ready] })
    if (others.length) groups.push({ label: `其他已导入的模型（选中后标记为支持${channelTypeLabels[type]}）`, options: others })
    return groups
  }

  return <div className="model-channels-panel">
    {messageContext}
    {error && <Alert
      type="error"
      showIcon
      message={error}
      action={<Button size="small" onClick={() => void reload()}>重试</Button>}
    />}

    <Card
      title="1. 连接"
      extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => openEditor(null)}>添加连接</Button>}
    >
      <Typography.Paragraph type="secondary">
        一个连接对应一家服务商的接口地址和密钥，只需要填一次。密钥保存在服务端，页面上不会再显示。
      </Typography.Paragraph>
      <Table<ModelChannel>
        rowKey="id"
        size="middle"
        loading={loading}
        columns={channelColumns}
        dataSource={channels}
        pagination={false}
        scroll={{ x: 760 }}
        locale={{ emptyText: <Empty description="还没有连接，先添加一个服务商" /> }}
      />
    </Card>

    <Card
      title="2. 模型"
      extra={<Segmented<ChannelModelType | 'all'>
        aria-label="按能力筛选"
        value={typeFilter}
        onChange={setTypeFilter}
        options={[
          { value: 'all', label: '全部' },
          ...channelModelTypes.map((type) => ({ value: type, label: channelTypeLabels[type] })),
        ]}
      />}
    >
      <Typography.Paragraph type="secondary">
        每个模型只需要导入一次。“能力”按模型代号自动识别，决定它能用在哪些用途上，识别得不对可以直接勾选修改。
      </Typography.Paragraph>
      <Table<ImportedModel>
        rowKey="key"
        size="middle"
        loading={loading}
        columns={modelColumns}
        dataSource={visibleModels}
        pagination={visibleModels.length > 10 ? { pageSize: 10, showSizeChanger: false } : false}
        scroll={{ x: 900 }}
        locale={{ emptyText: <Empty description={channels.length ? '在连接上点“拉取模型”来添加' : '添加连接后才能添加模型'} /> }}
      />
    </Card>

    <Card title="3. 默认模型">
      <Typography.Paragraph type="secondary">
        每种用途指定一个默认模型，同一个模型可以用在多种用途上。角色可以在自己的设置里另外指定。
      </Typography.Paragraph>
      <div className="model-default-grid">
        {channelModelTypes.map((type) => {
          const options = defaultOptions(type)
          return <div className="model-default-row" key={type}>
            <div>
              <Typography.Text strong>{channelTypeLabels[type]}模型</Typography.Text>
              <Typography.Paragraph type="secondary" style={{ margin: 0 }}>{defaultHelp[type]}</Typography.Paragraph>
            </div>
            <Select
              aria-label={`默认${channelTypeLabels[type]}模型`}
              value={defaults[type]?.id}
              placeholder={options.length ? '请选择' : '还没有可用的模型'}
              options={options}
              disabled={loading || options.length === 0 || busyId === `default-${type}`}
              loading={busyId === `default-${type}`}
              style={{ width: '100%' }}
              onChange={(value: string) => void chooseDefault(type, value)}
            />
          </div>
        })}
      </div>
    </Card>

    <ChannelEditor
      open={editorOpen}
      channel={editingChannel}
      onClose={() => { setEditorOpen(false); setEditingChannel(null) }}
      onSaved={channelSaved}
    />
    <ModelPicker
      channel={pickerChannel}
      onClose={() => setPickerChannel(null)}
      onAdded={(count) => {
        messageApi.success(`已导入 ${count} 个模型`)
        void reload()
      }}
    />
    <ModelParamsDrawer
      model={paramsModel}
      providerCode={paramsModel ? channelById.get(paramsModel.channelId)?.providerCode : undefined}
      onClose={() => setParamsModelKey(null)}
      onSaved={() => {
        setParamsModelKey(null)
        messageApi.success('模型已更新')
        void reload()
      }}
    />
  </div>
}

interface ChannelEditorValues {
  preset?: string
  name: string
  providerCode: ChannelProviderCode
  baseUrl: string
  apiKey?: string
}

interface ChannelEditorProps {
  open: boolean
  channel: ModelChannel | null
  onClose: () => void
  onSaved: (channel: ModelChannel, created: boolean) => void | Promise<void>
}

function ChannelEditor({ open, channel, onClose, onSaved }: ChannelEditorProps) {
  const [form] = Form.useForm<ChannelEditorValues>()
  const providerCode = Form.useWatch('providerCode', form)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<ModelTestResult | null>(null)
  const [error, setError] = useState('')
  const session = useRef(0)

  useEffect(() => {
    session.current += 1
    setSaving(false)
    setTesting(false)
    setTestResult(null)
    setError('')
    if (!open) return
    if (channel) {
      form.setFieldsValue({ name: channel.name, providerCode: channel.providerCode, baseUrl: channel.baseUrl, apiKey: '' })
    } else {
      const preset = channelPresets[0]
      form.setFieldsValue({
        preset: preset.code,
        name: preset.name,
        providerCode: preset.providerCode,
        baseUrl: preset.baseUrl,
        apiKey: '',
      })
    }
  }, [open, channel, form])

  function applyPreset(code: string) {
    const preset = channelPresets.find((item) => item.code === code)
    if (!preset) return
    setTestResult(null)
    form.setFieldsValue({
      name: preset.code === 'custom-openai' ? '' : preset.name,
      providerCode: preset.providerCode,
      baseUrl: preset.baseUrl,
    })
  }

  function input(values: ChannelEditorValues): ModelChannelInput {
    return {
      name: values.name.trim(),
      vendorCode: channel ? channel.vendorCode : values.preset ?? null,
      providerCode: values.providerCode,
      baseUrl: values.baseUrl.trim(),
      apiKey: values.providerCode === 'ollama' ? undefined : values.apiKey?.trim() || undefined,
    }
  }

  async function test() {
    const current = session.current
    setTestResult(null)
    let values: ChannelEditorValues
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    setTesting(true)
    try {
      const result = await testModelChannel(channel?.id ?? null, input(values))
      if (session.current === current) setTestResult(result)
    } catch (reason) {
      if (session.current === current) {
        setTestResult({ success: false, elapsedMillis: 0, message: errorText(reason, '连接测试失败') })
      }
    } finally {
      if (session.current === current) setTesting(false)
    }
  }

  async function save() {
    const current = session.current
    let values: ChannelEditorValues
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    setSaving(true)
    setError('')
    try {
      const saved = channel
        ? await updateModelChannel(channel.id, input(values))
        : await createModelChannel(input(values))
      if (session.current === current) await onSaved(saved, !channel)
    } catch (reason) {
      if (session.current === current) setError(errorText(reason, '连接保存失败'))
    } finally {
      if (session.current === current) setSaving(false)
    }
  }

  const keyHidden = providerCode === 'ollama'
  return <Modal
    title={channel ? '编辑连接' : '添加连接'}
    open={open}
    destroyOnHidden
    onCancel={onClose}
    footer={<Space>
      <Button loading={testing} disabled={saving} onClick={() => void test()}>测试连接</Button>
      <Button onClick={onClose}>取消</Button>
      <Button type="primary" loading={saving} disabled={testing} onClick={() => void save()}>保存</Button>
    </Space>}
  >
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
    <Form form={form} layout="vertical" requiredMark={false} onValuesChange={() => setTestResult(null)}>
      {!channel && <Form.Item name="preset" label="服务商">
        <Select
          aria-label="服务商"
          options={channelPresets.map((preset) => ({ value: preset.code, label: preset.name }))}
          onChange={applyPreset}
        />
      </Form.Item>}
      <Form.Item name="name" label="连接名称" rules={[{ required: true, whitespace: true, message: '请输入连接名称' }, { max: 64, message: '不能超过 64 个字' }]}>
        <Input aria-label="连接名称" placeholder="例如 DeepSeek" />
      </Form.Item>
      <Form.Item name="providerCode" label="接口协议" extra={channel ? '接口协议创建后不能修改' : undefined}>
        <Select
          aria-label="接口协议"
          disabled={Boolean(channel)}
          options={Object.entries(channelProviderLabels).map(([value, label]) => ({ value, label }))}
        />
      </Form.Item>
      <Form.Item
        name="baseUrl"
        label="接口地址"
        rules={[
          { required: true, whitespace: true, message: '请输入接口地址' },
          { pattern: /^\s*https?:\/\/\S+\s*$/i, message: '应为 http 或 https 开头的地址' },
        ]}
      >
        <Input aria-label="接口地址" placeholder="https://api.example.com/v1" spellCheck={false} />
      </Form.Item>
      {!keyHidden && <Form.Item
        name="apiKey"
        label="API 密钥"
        extra={channel?.credentialConfigured ? '已保存密钥，留空会保留原值' : '本地服务不需要密钥时可以留空'}
      >
        <Input.Password aria-label="API 密钥" autoComplete="new-password" />
      </Form.Item>}
    </Form>
    {testResult && <Alert
      type={testResult.success ? 'success' : 'error'}
      showIcon
      message={testResult.success ? `${testResult.message}（${testResult.elapsedMillis} ms）` : testResult.message}
    />}
  </Modal>
}

interface ModelPickerProps {
  channel: ModelChannel | null
  onClose: () => void
  onAdded: (count: number) => void
}

function ModelPicker({ channel, onClose, onAdded }: ModelPickerProps) {
  const [upstream, setUpstream] = useState<UpstreamModel[]>([])
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [saveError, setSaveError] = useState('')
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [manualId, setManualId] = useState('')
  const [saving, setSaving] = useState(false)
  const session = useRef(0)

  const load = useCallback(async (target: ModelChannel) => {
    const current = ++session.current
    setLoading(true)
    setLoadError('')
    try {
      const list = await listUpstreamModels(target.id)
      if (session.current !== current) return
      setUpstream(list)
    } catch (reason) {
      if (session.current !== current) return
      setUpstream([])
      setLoadError(errorText(reason, '模型列表拉取失败'))
    } finally {
      if (session.current === current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    session.current += 1
    setUpstream([])
    setSelected([])
    setSearch('')
    setManualId('')
    setLoadError('')
    setSaveError('')
    setSaving(false)
    if (channel) void load(channel)
  }, [channel, load])

  const rows = useMemo(() => {
    const keyword = search.trim().toLowerCase()
    return keyword ? upstream.filter((model) => model.id.toLowerCase().includes(keyword)) : upstream
  }, [upstream, search])

  /** 导入不需要先选类型：每个模型按代号识别出的能力各建一条配置，导入后在模型列表里可以改。 */
  async function importModels(modelIds: string[]) {
    if (!channel || modelIds.length === 0) return
    const items: AddChannelModelInput[] = modelIds.flatMap((modelId) => suggestCapabilities(modelId, channel.providerCode)
      .map((modelType) => ({ modelType, modelId })))
    const current = session.current
    setSaving(true)
    setSaveError('')
    try {
      const created = await addChannelModels(channel.id, items)
      if (session.current !== current) return
      const count = new Set(created.map((model) => model.modelId)).size
      if (count === 0) {
        setSaveError('这些模型已经导入过了')
        return
      }
      onAdded(count)
      onClose()
    } catch (reason) {
      if (session.current === current) setSaveError(errorText(reason, '模型导入失败'))
    } finally {
      if (session.current === current) setSaving(false)
    }
  }

  const columns: ColumnsType<UpstreamModel> = [
    { title: '模型代号', dataIndex: 'id', render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
    {
      title: '识别出的能力',
      width: 170,
      render: (_, row) => <Space size={4} wrap>
        {(row.addedTypes.length ? row.addedTypes : suggestCapabilities(row.id, channel?.providerCode ?? 'openai'))
          .map((type) => <Tag key={type}>{channelTypeLabels[type]}</Tag>)}
      </Space>,
    },
    {
      title: '状态',
      width: 90,
      render: (_, row) => row.addedTypes.length ? <Tag color="success">已导入</Tag> : null,
    },
  ]

  return <Modal
    title={channel ? `从“${channel.name}”导入模型` : '导入模型'}
    open={Boolean(channel)}
    width={720}
    destroyOnHidden
    onCancel={onClose}
    footer={<Space>
      <Button onClick={onClose}>取消</Button>
      <Button
        type="primary"
        loading={saving}
        disabled={selected.length === 0}
        onClick={() => void importModels(selected)}
      >{`导入所选（${selected.length}）`}</Button>
    </Space>}
  >
    {loadError && <Alert
      type="warning"
      showIcon
      message={loadError}
      description="拉取不到列表时，可以在下面手动填写模型代号。"
      action={channel ? <Button size="small" onClick={() => void load(channel)}>重试</Button> : undefined}
      style={{ marginBottom: 16 }}
    />}
    {saveError && <Alert type="error" showIcon message={saveError} style={{ marginBottom: 16 }} />}
    <Typography.Paragraph type="secondary">
      勾选要用的模型即可，不需要先选类型。能力识别得不对，导入后可以在模型列表里修改。
    </Typography.Paragraph>
    <Input.Search
      aria-label="搜索模型代号"
      allowClear
      placeholder="搜索模型代号"
      value={search}
      onChange={(event) => setSearch(event.target.value)}
      style={{ marginBottom: 12 }}
    />
    <Table<UpstreamModel>
      rowKey="id"
      size="small"
      loading={loading}
      columns={columns}
      dataSource={rows}
      pagination={rows.length > 8 ? { pageSize: 8, showSizeChanger: false, size: 'small' } : false}
      rowSelection={{
        selectedRowKeys: selected,
        preserveSelectedRowKeys: true,
        onChange: (keys) => setSelected(keys.map(String)),
        getCheckboxProps: (row) => ({ disabled: row.addedTypes.length > 0 }),
      }}
      locale={{ emptyText: <Empty description={loading ? '正在拉取' : '没有可选的模型'} /> }}
    />
    <Typography.Paragraph type="secondary" style={{ margin: '16px 0 8px' }}>
      列表里没有的模型，可以直接填写服务商给出的模型代号：
    </Typography.Paragraph>
    <Space.Compact style={{ width: '100%' }}>
      <Input
        aria-label="手动填写模型代号"
        placeholder="例如 deepseek-chat"
        value={manualId}
        maxLength={200}
        spellCheck={false}
        onChange={(event) => setManualId(event.target.value)}
      />
      <Button
        loading={saving}
        disabled={!manualId.trim()}
        onClick={() => void importModels([manualId.trim()])}
      >手动导入</Button>
    </Space.Compact>
  </Modal>
}

interface ModelParamsDrawerProps {
  model: ImportedModel | null
  providerCode: ChannelProviderCode | undefined
  onClose: () => void
  onSaved: () => void
}

type ParamValues = Record<string, ChannelModelParamValue | null | undefined>

interface ModelParamsValues {
  modelName: string
  /** 按能力分开保存：对话有对话的参数，向量有向量的参数。 */
  params?: Partial<Record<ChannelModelType, ParamValues>>
}

type CapabilityFields = Partial<Record<ChannelModelType, ModelProviderField[]>>

function ModelParamsDrawer({ model, providerCode, onClose, onSaved }: ModelParamsDrawerProps) {
  const [form] = Form.useForm<ModelParamsValues>()
  const [fields, setFields] = useState<CapabilityFields>({})
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const session = useRef(0)
  // 抽屉打开期间列表会刷新，只在换了一个模型时才重置表单。
  const modelKey = model?.key ?? null
  const opened = useRef(model)
  opened.current = model

  useEffect(() => {
    const current = ++session.current
    const target = opened.current
    setFields({})
    setError('')
    setSaving(false)
    if (!target) return
    const rows = modelRows(target)
    form.setFieldsValue({
      modelName: target.modelName,
      params: Object.fromEntries(rows.map((row) => [row.modelType, { ...row.params }])),
    })
    setLoading(true)
    Promise.all(rows.map(async (row) => {
      const providers = await listProviderTypes(row.modelType)
      const provider = providers.find((item) => item.providerCode === providerCode)
      // 地址、密钥和模型代号由连接决定，这里只留下可以调的参数。
      const editable = (provider?.fields ?? []).filter((field) => !managedFieldKeys.has(field.key)
        && !isCredentialField(field) && field.type !== 'dict')
      return [row.modelType, editable] as const
    }))
      .then((entries) => {
        if (session.current === current) setFields(Object.fromEntries(entries))
      })
      .catch((reason: unknown) => {
        if (session.current === current) setError(errorText(reason, '模型参数加载失败'))
      })
      .finally(() => {
        if (session.current === current) setLoading(false)
      })
  }, [modelKey, providerCode, form])

  async function save() {
    if (!model) return
    const current = session.current
    let values: ModelParamsValues
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    setSaving(true)
    setError('')
    try {
      for (const row of modelRows(model)) {
        const params: Record<string, ChannelModelParamValue | null> = {}
        for (const field of fields[row.modelType] ?? []) {
          const value = values.params?.[row.modelType]?.[field.key]
          params[field.key] = value === undefined || value === null || value === '' ? null : value
        }
        await updateChannelModel(row.id, { modelName: values.modelName.trim(), params })
      }
      if (session.current === current) onSaved()
    } catch (reason) {
      if (session.current === current) setError(errorText(reason, '模型保存失败'))
    } finally {
      if (session.current === current) setSaving(false)
    }
  }

  const sections = model
    ? channelModelTypes.filter((type) => model.rows[type] && (fields[type]?.length ?? 0) > 0)
    : []
  return <Drawer
    title="模型参数"
    aria-label="模型参数"
    open={Boolean(model)}
    width={520}
    destroyOnHidden
    onClose={onClose}
    footer={<Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
      <Button onClick={onClose}>取消</Button>
      <Button type="primary" loading={saving} disabled={loading} onClick={() => void save()}>保存</Button>
    </Space>}
  >
    <Spin spinning={loading}>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      <Form form={form} layout="vertical" requiredMark={false}>
        <Form.Item
          name="modelName"
          label="显示名称"
          extra={model ? `模型代号：${model.modelId}` : undefined}
          rules={[{ required: true, whitespace: true, message: '请输入显示名称' }, { max: 50, message: '不能超过 50 个字' }]}
        >
          <Input aria-label="显示名称" />
        </Form.Item>
        {sections.map((type) => <div key={type}>
          {sections.length > 1 && <Divider orientation="left" plain>{`用于${channelTypeLabels[type]}时`}</Divider>}
          <ModelFieldEditor modelType={type} fields={fields[type] ?? []} namePrefix={['params', type]} />
        </div>)}
      </Form>
    </Spin>
  </Drawer>
}
