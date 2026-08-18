import { AppstoreOutlined, CloudServerOutlined, DownloadOutlined, PlusOutlined, ThunderboltOutlined, UploadOutlined } from '@ant-design/icons'
import { ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components'
import { Alert, Button, Divider, Dropdown, Form, Input, message, Modal, Select, Space, Steps, Tag, Typography } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import {
  approveMcpTools, createCapability, createSkillFromPackage, deleteCapability, getCapability, getCapabilitySecretStatus,
  downloadSkillPackage, importLocalMcpConfig, listCapabilities, listMcpTools, listPluginExecutors, listSkillPackages,
  previewCapabilityRoute, publishCapability,
  saveCapabilitySecret, setCapabilityStatus,
  syncMcpTools, testMcpConnection, updateCapability, type Capability, type CapabilitySaveInput,
  type McpToolSnapshot, type PluginExecutor, type SkillPackageVersion, type SkillTool,
} from '../../api/capabilities'
import { AdminPage } from './AdminPage'
import { adminErrorMessage } from './adminErrors'
import { McpEditorModal } from './McpEditorModal'
import { SkillEditorModal, type SkillEditorSeed, type SkillToolOption, type SkillToolParameter } from './SkillEditorModal'
import { SkillPackageImportModal } from './SkillPackageImportModal'

type Editor = { kind: 'skill' | 'plugin' | 'mcp'; capability: Capability | null } | null

interface PluginFormValue {
  name: string
  description?: string
  executorName: string
  inputSchema: string
  configSchema: string
  secretFields?: string
}

const typeLabels = { SKILL: 'Skill', PLUGIN: 'Plugin', MCP_SERVER: 'MCP 服务' } as const
const statusLabels = { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用' } as const
const statusColors = { DRAFT: 'default', PUBLISHED: 'green', DISABLED: 'red' } as const

function mergeCapabilities(current: Capability[], incoming: Capability[]) {
  const byId = new Map(current.map((item) => [item.id, item]))
  incoming.forEach((item) => byId.set(item.id, item))
  return [...byId.values()]
}

function option(tool: SkillTool, label: string, parameters: SkillToolParameter[] = []): SkillToolOption {
  return { key: `${tool.toolType}:${tool.toolRefId}:${tool.toolName}`, label, tool, parameters }
}

function toolParameters(schemas: Record<string, unknown>[], excluded: string[], defaults: Record<string, unknown>) {
  const values = new Map<string, SkillToolParameter>()
  const excludedNames = new Set(excluded)
  schemas.forEach((schema) => {
    const properties = schema.properties
    if (!properties || typeof properties !== 'object' || Array.isArray(properties)) return
    Object.entries(properties).forEach(([name, raw]) => {
      if (excludedNames.has(name) || !raw || typeof raw !== 'object' || Array.isArray(raw)) return
      const property = raw as Record<string, unknown>
      const schemaType = property.type
      const type = schemaType === 'boolean' ? 'boolean' : schemaType === 'integer' || schemaType === 'number' ? 'number' : 'string'
      values.set(name, {
        name,
        label: typeof property.description === 'string' && property.description.trim() ? property.description.trim() : name,
        type,
      })
    })
  })
  Object.keys(defaults).forEach((name) => {
    if (!values.has(name)) values.set(name, { name, label: name, type: typeof defaults[name] === 'number' ? 'number' : 'string' })
  })
  return [...values.values()]
}

function mcpParameters(snapshot: McpToolSnapshot) {
  try {
    const schema = JSON.parse(snapshot.inputSchemaJson) as unknown
    return schema && typeof schema === 'object' && !Array.isArray(schema)
      ? toolParameters([schema as Record<string, unknown>], [], {})
      : []
  } catch {
    return []
  }
}

function parseObject(value: string, field: string) {
  try {
    const parsed: unknown = JSON.parse(value)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error()
    return parsed as Record<string, unknown>
  } catch {
    throw new Error(`${field}必须是 JSON 对象`)
  }
}

function CapabilityWorkflow() {
  return <section className="capability-workflow" aria-label="能力配置流程">
    <Steps items={[
      {
        title: '准备工具',
        description: 'Plugin 使用已部署执行器，MCP 接入外部工具。',
      },
      {
        title: '创建并发布 Skill',
        description: '组合工具，配置触发规则、提示词和默认参数。',
      },
      {
        title: '绑定到设备',
        description: <Space direction="vertical" size={0}>
          <span>设备只获得已绑定的已发布 Skill。</span>
          <Button type="link" size="small" href="/devices">去设备页绑定</Button>
        </Space>,
      },
    ]} />
  </section>
}

function PluginEditor({ editor, executors, executorError, secretStatus, saving, error, onCancel, onSave, onSaveSecret }: {
  editor: Editor
  executors: PluginExecutor[]
  executorError: string
  secretStatus: Record<string, boolean>
  saving: boolean
  error: string
  onCancel: () => void
  onSave: (input: CapabilitySaveInput) => Promise<void>
  onSaveSecret: (name: string, value: string) => Promise<void>
}) {
  const [form] = Form.useForm<PluginFormValue>()
  const [secretValues, setSecretValues] = useState<Record<string, string>>({})
  const capability = editor?.kind === 'plugin' ? editor.capability : null
  const open = editor?.kind === 'plugin'
  const currentExecutor = capability?.plugin?.executorName
  const available = new Set(executors.map((item) => item.name))
  const options = executors.map((item) => ({
    value: item.name, label: `${item.description || item.name} / ${item.name}`,
  }))
  if (currentExecutor && !available.has(currentExecutor)) {
    options.push({ value: currentExecutor, label: `不可用 / ${currentExecutor}` })
  }
  useEffect(() => {
    if (!open) return
    const registered = executors.find((item) => item.name === capability?.plugin?.executorName)
    form.setFieldsValue({
      name: capability?.name ?? '', description: capability?.description ?? '',
      executorName: capability?.plugin?.executorName ?? '',
      inputSchema: JSON.stringify(registered?.inputSchema ?? capability?.plugin?.inputSchema ?? {}, null, 2),
      configSchema: JSON.stringify(capability?.plugin?.configSchema ?? {}, null, 2),
      secretFields: capability?.plugin?.secretFields.join(', ') ?? '',
    })
    setSecretValues({})
  }, [capability, executors, form, open])
  async function submit(values: PluginFormValue) {
    await onSave({
      type: 'PLUGIN', name: values.name.trim(), description: values.description?.trim() || null,
      plugin: {
        executorName: values.executorName.trim(), inputSchema: parseObject(values.inputSchema, '输入 Schema'),
        configSchema: parseObject(values.configSchema, '配置 Schema'),
        secretFields: values.secretFields?.split(',').map((item) => item.trim()).filter(Boolean) ?? [],
        defaultConfig: capability?.plugin?.defaultConfig ?? {},
      },
    })
  }
  return <Modal title={capability ? '编辑 Plugin' : '新建 Plugin'} open={open} onCancel={onCancel}
    onOk={() => form.submit()} okText="保存草稿" confirmLoading={saving} closable={!saving} maskClosable={!saving} destroyOnHidden>
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
    <Form form={form} layout="vertical" onFinish={submit} requiredMark="optional">
      {executorError && <Alert type="warning" showIcon message={executorError} style={{ marginBottom: 16 }} />}
      {currentExecutor && !available.has(currentExecutor) && <Alert type="error" showIcon
        message={`运行时未登记执行器 ${currentExecutor}`} style={{ marginBottom: 16 }} />}
      <Form.Item name="name" label="名称" rules={[{ required: true, whitespace: true }]}><Input autoFocus /></Form.Item>
      <Form.Item name="description" label="用途说明"><Input.TextArea rows={2} /></Form.Item>
      <Form.Item name="executorName" label="执行器标识" rules={[{ required: true }]} extra="只能选择 xiaozhi-server 已部署并登记的执行器。">
        <Select disabled={Boolean(capability) || executors.length === 0} options={options}
          onChange={(name) => {
            const executor = executors.find((item) => item.name === name)
            if (executor) form.setFieldValue('inputSchema', JSON.stringify(executor.inputSchema, null, 2))
          }} />
      </Form.Item>
      <Form.Item name="inputSchema" label="输入 Schema" rules={[{ required: true }]}><Input.TextArea rows={5} spellCheck={false} disabled /></Form.Item>
      <Form.Item name="configSchema" label="配置 Schema" rules={[{ required: true }]}><Input.TextArea rows={4} spellCheck={false} /></Form.Item>
      <Form.Item name="secretFields" label="密钥字段" extra="多个字段用逗号分隔，密钥值在保存能力后单独配置。"><Input /></Form.Item>
    </Form>
    {capability?.plugin?.secretFields.length ? <>
      <Divider orientation="left" plain>密钥</Divider>
      <Space direction="vertical" style={{ width: '100%' }}>
        {capability.plugin.secretFields.map((name) => <Space key={name} align="end" wrap>
          <Tag color={secretStatus[name.toLowerCase()] ? 'green' : 'default'}>
            {name} {secretStatus[name.toLowerCase()] ? '已配置' : '未配置'}
          </Tag>
          <Form.Item label={`${name} 新密钥值`} style={{ marginBottom: 0 }}>
            <Input.Password aria-label={`${name} 新密钥值`} value={secretValues[name] ?? ''}
              onChange={(event) => setSecretValues((current) => ({ ...current, [name]: event.target.value }))}
              autoComplete="new-password" />
          </Form.Item>
          <Button disabled={!secretValues[name] || saving} onClick={async () => {
            await onSaveSecret(name, secretValues[name])
            setSecretValues((current) => ({ ...current, [name]: '' }))
          }}>保存 {name}</Button>
        </Space>)}
      </Space>
    </> : capability ? <Typography.Text type="secondary">此 Plugin 未声明密钥字段。</Typography.Text> : null}
  </Modal>
}

export function CapabilityManagementPage() {
  const actionRef = useRef<ActionType>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const sequence = useRef(0)
  const mounted = useRef(false)
  const [listError, setListError] = useState('')
  const [modalError, setModalError] = useState('')
  const [editor, setEditor] = useState<Editor>(null)
  const [saving, setSaving] = useState(false)
  const [catalog, setCatalog] = useState<Capability[]>([])
  const [mcpTools, setMcpTools] = useState<Record<string, McpToolSnapshot[]>>({})
  const [secretStatus, setSecretStatus] = useState<Record<string, boolean>>({})
  const [importOpen, setImportOpen] = useState(false)
  const [importText, setImportText] = useState('')
  const [importError, setImportError] = useState('')
  const [importing, setImporting] = useState(false)
  const [skillPackageImportOpen, setSkillPackageImportOpen] = useState(false)
  const [skillSeed, setSkillSeed] = useState<SkillEditorSeed | null>(null)
  const [skillPackageVersions, setSkillPackageVersions] = useState<SkillPackageVersion[]>([])
  const [pluginExecutors, setPluginExecutors] = useState<PluginExecutor[]>([])
  const [executorError, setExecutorError] = useState('')

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; controllerRef.current?.abort(); sequence.current += 1 }
  }, [])

  const loadPluginExecutors = useCallback(async () => {
    setExecutorError('')
    try {
      const values = await listPluginExecutors()
      if (!mounted.current) return
      setPluginExecutors(values)
    } catch (reason) {
      if (mounted.current) setExecutorError(adminErrorMessage(reason, 'Plugin 执行器目录加载失败'))
    }
  }, [])

  useEffect(() => { void loadPluginExecutors() }, [loadPluginExecutors])

  const refreshMcpTools = useCallback(async (capabilities: Capability[]) => {
    const servers = capabilities.filter((item) => item.type === 'MCP_SERVER')
    const settled = await Promise.allSettled(servers.map(async (server) => [server.id, await listMcpTools(server.id)] as const))
    if (!mounted.current) return
    setMcpTools((current) => {
      const next = { ...current }
      settled.forEach((result) => { if (result.status === 'fulfilled') next[result.value[0]] = result.value[1] })
      return next
    })
  }, [])

  const request = useCallback(async (params: { current?: number; pageSize?: number; keyword?: string; type?: string; status?: string }) => {
    const requestId = ++sequence.current
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    setListError('')
    try {
      const result = await listCapabilities({
        type: params.type as 'SKILL' | 'PLUGIN' | 'MCP_SERVER' | undefined,
        status: params.status as 'DRAFT' | 'PUBLISHED' | 'DISABLED' | undefined,
        keyword: params.keyword?.trim() || undefined,
        page: params.current ?? 1,
        limit: params.pageSize ?? 20,
      }, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || requestId !== sequence.current) return { data: [], total: 0, success: false }
      setCatalog((current) => mergeCapabilities(current, result.list))
      void refreshMcpTools(result.list)
      return { data: result.list, total: result.total, success: true }
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && requestId === sequence.current) {
        setListError(adminErrorMessage(reason, '能力列表加载失败'))
      }
      return { data: [], total: 0, success: false }
    }
  }, [refreshMcpTools])

  const toolOptions = useMemo(() => {
    const values = new Map<string, SkillToolOption>()
    const registeredPlugins = new Set(pluginExecutors.map((item) => item.name))
    const plugins = new Map(catalog.filter((item) => item.type === 'PLUGIN' && item.plugin)
      .map((item) => [item.id, item] as const))
    const snapshots = new Map(Object.values(mcpTools).flat().map((item) => [item.id, item]))
    const activeMcpTools = new Set(Object.values(mcpTools).flat()
      .filter((tool) => tool.approved === 1 && tool.status === 'ACTIVE').map((tool) => tool.id))
    catalog.forEach((capability) => {
      if (capability.type === 'PLUGIN' && capability.plugin && registeredPlugins.has(capability.plugin.executorName)) {
        const tool: SkillTool = { toolType: 'PLUGIN', toolRefId: capability.id,
          toolName: capability.plugin.executorName, alias: null, purpose: capability.description,
          defaultParams: capability.plugin.defaultConfig, required: true, sortOrder: 0 }
        const item = option(tool, `${capability.name} / ${tool.toolName}`, toolParameters(
          [capability.plugin.inputSchema, capability.plugin.configSchema],
          capability.plugin.secretFields,
          tool.defaultParams,
        ))
        values.set(item.key, item)
      }
      if (capability.type === 'MCP_SERVER') {
        ;(mcpTools[capability.id] ?? []).filter((tool) => tool.approved === 1 && tool.status === 'ACTIVE').forEach((snapshot) => {
          const tool: SkillTool = { toolType: 'MCP', toolRefId: snapshot.id, toolName: snapshot.toolName,
            alias: null, purpose: capability.description, defaultParams: {}, required: true, sortOrder: 0 }
          const item = option(tool, `${capability.name} / ${tool.toolName}`, mcpParameters(snapshot))
          values.set(item.key, item)
        })
      }
      capability.tools.forEach((tool) => {
        if (tool.toolType === 'PLUGIN' && !registeredPlugins.has(tool.toolName)) return
        if (tool.toolType === 'MCP' && !activeMcpTools.has(tool.toolRefId)) return
        const pluginCapability = tool.toolType === 'PLUGIN' ? plugins.get(tool.toolRefId) : null
        const plugin = pluginCapability?.plugin
        const snapshot = tool.toolType === 'MCP' ? snapshots.get(tool.toolRefId) : null
        if (plugin && pluginCapability) {
          const canonicalTool: SkillTool = {
            ...tool,
            purpose: pluginCapability.description,
            defaultParams: plugin.defaultConfig,
          }
          const item = option(canonicalTool, `${pluginCapability.name} / ${tool.toolName}`, toolParameters(
            [plugin.inputSchema, plugin.configSchema], plugin.secretFields, plugin.defaultConfig,
          ))
          values.set(item.key, item)
          return
        }
        const parameters = plugin
          ? toolParameters([plugin.inputSchema, plugin.configSchema], plugin.secretFields, tool.defaultParams)
          : snapshot ? mcpParameters(snapshot) : toolParameters([], [], tool.defaultParams)
        const item = option(tool, `${capability.name} / ${tool.toolName}`, parameters)
        values.set(item.key, item)
      })
    })
    return [...values.values()]
  }, [catalog, mcpTools, pluginExecutors])

  function closeEditor() {
    if (saving) return
    setEditor(null)
    setModalError('')
    setSecretStatus({})
    setSkillSeed(null)
    setSkillPackageVersions([])
  }

  async function openEditor(row: Capability) {
    setModalError('')
    try {
      const [detail, packages] = row.type === 'SKILL'
        ? await Promise.all([getCapability(row.id), listSkillPackages(row.id)])
        : [await getCapability(row.id), [] as SkillPackageVersion[]]
      if (detail.type === 'MCP_SERVER') {
        const [tools, secrets] = await Promise.all([listMcpTools(detail.id), getCapabilitySecretStatus(detail.id)])
        setMcpTools((current) => ({ ...current, [detail.id]: tools }))
        setSecretStatus(secrets)
        setEditor({ kind: 'mcp', capability: detail })
      } else if (detail.type === 'PLUGIN') {
        setSecretStatus(await getCapabilitySecretStatus(detail.id))
        setEditor({ kind: 'plugin', capability: detail })
      } else {
        setSkillSeed(null)
        setSkillPackageVersions(packages)
        setEditor({ kind: 'skill', capability: detail })
      }
    } catch (reason) {
      setListError(adminErrorMessage(reason, '能力详情加载失败'))
    }
  }

  async function save(input: CapabilitySaveInput) {
    setSaving(true)
    setModalError('')
    try {
      if (editor?.capability) await updateCapability(editor.capability.id, input)
      else await createCapability(input)
      message.success(editor?.capability ? '能力草稿已更新' : '能力草稿已创建')
      closeEditor()
      await actionRef.current?.reload()
    } catch (reason) {
      setModalError(adminErrorMessage(reason, '能力保存失败'))
    } finally {
      setSaving(false)
    }
  }

  async function saveSecret(name: string, value: string) {
    if (!editor?.capability) return
    await saveCapabilitySecret(editor.capability.id, name, value)
    setSecretStatus((current) => ({ ...current, [name.toLowerCase()]: true }))
    message.success('密钥已保存')
  }

  async function approve(ids: string[]) {
    if (!editor?.capability) return
    const tools = await approveMcpTools(editor.capability.id, ids)
    setMcpTools((current) => ({ ...current, [editor.capability!.id]: tools }))
    message.success('工具白名单已更新')
  }

  async function testMcp() {
    if (!editor?.capability) return
    setSaving(true)
    setModalError('')
    try {
      const result = await testMcpConnection(editor.capability.id)
      if (!result.success) {
        setModalError(`MCP 连接失败${result.errorClass ? ` ${result.errorClass}` : ''}`)
        return
      }
      const detail = await getCapability(editor.capability.id)
      setEditor({ kind: 'mcp', capability: detail })
      message.success('MCP 连接正常')
    } catch (reason) {
      setModalError(adminErrorMessage(reason, 'MCP 连接测试失败'))
    } finally {
      setSaving(false)
    }
  }

  async function syncMcp() {
    if (!editor?.capability) return
    setSaving(true)
    setModalError('')
    try {
      const result = await syncMcpTools(editor.capability.id)
      setMcpTools((current) => ({ ...current, [editor.capability!.id]: result.tools }))
      if (!result.success) {
        setModalError(`MCP 工具同步失败${result.errorClass ? ` ${result.errorClass}` : ''}`)
        return
      }
      message.success('MCP 工具已同步，新增工具仍需手动加入白名单')
    } catch (reason) {
      setModalError(adminErrorMessage(reason, 'MCP 工具同步失败'))
    } finally {
      setSaving(false)
    }
  }

  async function importMcp() {
    setImportError('')
    let document: Record<string, unknown>
    try {
      document = parseObject(importText, 'MCP 配置 JSON')
    } catch (reason) {
      setImportError(reason instanceof Error ? reason.message : 'MCP 配置 JSON 格式错误')
      return
    }
    setImporting(true)
    try {
      const result = await importLocalMcpConfig(document)
      message.success(`已导入 ${result.imported.length} 个 MCP 服务，跳过 ${result.skipped.length} 个已有服务`)
      setImportOpen(false)
      setImportText('')
      await actionRef.current?.reload()
    } catch (reason) {
      setImportError(adminErrorMessage(reason, 'MCP 本地配置导入失败'))
    } finally {
      setImporting(false)
    }
  }

  async function handleSkillPackageImported(result: { capabilityId: string | null; validation: { status: string } }, file?: File) {
    if (!file || result.validation.status !== 'VALID') return
    setImporting(true)
    try {
      await createSkillFromPackage(file)
      message.success('Skill 包已保存为草稿')
      setSkillPackageImportOpen(false)
      await actionRef.current?.reload()
    } catch (reason) {
      message.error(adminErrorMessage(reason, 'Skill 包保存失败'))
    } finally {
      setImporting(false)
    }
  }

  async function downloadPackage(capabilityId: string, version: number) {
    try {
      const blob = await downloadSkillPackage(capabilityId, version)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `${capabilityId.replace(/[^A-Za-z0-9._-]/g, '_')}-${version}.skill.zip`
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (reason) {
      message.error(adminErrorMessage(reason, 'Skill 包下载失败'))
    }
  }

  function confirmPublish(row: Capability) {
    Modal.confirm({
      title: `发布 ${row.name}？`,
      content: row.publishedVersion ? `将生成 v${row.publishedVersion + 1}，已有版本保持不变。` : '将生成第一个不可变版本。',
      okText: '确认发布', cancelText: '取消',
      onOk: async () => { await publishCapability(row.id); message.success('能力已发布'); await actionRef.current?.reload() },
    })
  }

  const columns: ProColumns<Capability>[] = [
    { title: '关键词', dataIndex: 'keyword', hideInTable: true },
    { title: '能力类型', dataIndex: 'type', valueType: 'select', valueEnum: {
      SKILL: { text: 'Skill' }, PLUGIN: { text: 'Plugin' }, MCP_SERVER: { text: 'MCP 服务' },
    }, render: (_, row) => <Tag>{typeLabels[row.type]}</Tag> },
    { title: '名称', dataIndex: 'name', hideInSearch: true, ellipsis: true },
    { title: '说明', dataIndex: 'description', hideInSearch: true, ellipsis: true },
    { title: '状态', dataIndex: 'status', valueType: 'select', valueEnum: {
      DRAFT: { text: '草稿' }, PUBLISHED: { text: '已发布' }, DISABLED: { text: '已停用' },
    }, render: (_, row) => <Space size={4} wrap>
      <Tag color={statusColors[row.status]}>{statusLabels[row.status]}</Tag>
      {row.type === 'PLUGIN' && row.plugin && !pluginExecutors.some((item) => item.name === row.plugin!.executorName)
        && <Tag color="red">执行器不可用</Tag>}
    </Space> },
    { title: '版本', hideInSearch: true, render: (_, row) => <Space size={4} wrap>
      <span>{row.publishedVersion ? `v${row.publishedVersion}` : '未发布'}</span>
      {row.type === 'SKILL' && row.packageVersion && <Tag color="blue">包 v{row.packageVersion}</Tag>}
    </Space> },
    { title: '包校验', hideInSearch: true, render: (_, row) => row.type === 'SKILL' && row.packageValidationStatus
      ? <Tag color={row.packageValidationStatus === 'VALID' ? 'green' : 'orange'}>{row.packageValidationStatus}</Tag> : '-' },
    { title: '包来源', hideInSearch: true, render: (_, row) => row.type === 'SKILL' && row.packageSource ? <Tag>{row.packageSource}</Tag> : '-' },
    { title: '包摘要', hideInSearch: true, width: 170, render: (_, row) => row.type === 'SKILL' && row.packageSha256
      ? <Typography.Text copyable={{ text: row.packageSha256 }}>{`${row.packageSha256.slice(0, 12)}…`}</Typography.Text> : '-' },
    { title: '操作', valueType: 'option', width: 260, render: (_, row) => <Space wrap>
      <Button onClick={() => void openEditor(row)}>编辑</Button>
      <Button type="primary" ghost onClick={() => confirmPublish(row)}>发布</Button>
      {row.type === 'SKILL' && row.packageVersion && <Button icon={<DownloadOutlined />}
        onClick={() => void downloadPackage(row.id, row.packageVersion!)}>下载包</Button>}
      <Button onClick={async () => { await setCapabilityStatus(row.id, row.status === 'DISABLED' ? 'PUBLISHED' : 'DISABLED'); await actionRef.current?.reload() }}>{row.status === 'DISABLED' ? '启用' : '停用'}</Button>
      <Button danger onClick={() => Modal.confirm({ title: `删除 ${row.name}？`, okButtonProps: { danger: true }, okText: '确认删除', cancelText: '取消', onOk: async () => { await deleteCapability(row.id); await actionRef.current?.reload() } })}>删除</Button>
    </Space> },
  ]

  const createMenu = { items: [
    { key: 'skill', icon: <ThunderboltOutlined />, label: '新建 Skill', onClick: () => {
      setSkillSeed(null); setEditor({ kind: 'skill', capability: null })
    } },
    { key: 'plugin', icon: <AppstoreOutlined />, label: '新建 Plugin', onClick: () => setEditor({ kind: 'plugin', capability: null }) },
    { key: 'mcp', icon: <CloudServerOutlined />, label: '新建 MCP', onClick: () => setEditor({ kind: 'mcp', capability: null }) },
    { key: 'import-mcp', icon: <UploadOutlined />, label: '导入本地 MCP JSON', onClick: () => {
      setImportError(''); setImportOpen(true)
    } },
  ] }

  return <AdminPage title="能力中心" subTitle="Skill 只组合已登记工具，发布版本不可原地覆盖。"
    error={listError} onRetry={() => void actionRef.current?.reload()}
    actions={<Space>
      <Dropdown menu={createMenu}><Button type="primary" icon={<PlusOutlined />}>新建能力</Button></Dropdown>
      <Button aria-label="上传 Skill 包" icon={<UploadOutlined />} onClick={() => setSkillPackageImportOpen(true)}>上传 Skill 包</Button>
    </Space>}>
    <CapabilityWorkflow />
    {executorError && <Alert className="capability-executor-alert" type="warning" showIcon message={executorError}
      description="Skill 和能力列表仍可查看；新建 Plugin 或选择 Plugin 工具前需要恢复执行器目录。"
      action={<Button size="small" onClick={() => void loadPluginExecutors()}>重试执行器目录</Button>} />}
    <ProTable<Capability> actionRef={actionRef} rowKey="id" columns={columns} request={request}
      scroll={{ x: 980 }} pagination={{ defaultPageSize: 20 }} options={false} search={{ labelWidth: 'auto' }} />
    <SkillEditorModal open={editor?.kind === 'skill'} capability={editor?.kind === 'skill' ? editor.capability : null}
      seed={skillSeed} packageVersions={skillPackageVersions} toolOptions={toolOptions} saving={saving} error={modalError} onCancel={closeEditor} onSave={save}
      onPreview={previewCapabilityRoute} onDownload={downloadPackage} />
    <PluginEditor editor={editor} executors={pluginExecutors} executorError={executorError}
      secretStatus={secretStatus} saving={saving} error={modalError} onCancel={closeEditor}
      onSave={save} onSaveSecret={saveSecret} />
    <McpEditorModal open={editor?.kind === 'mcp'} capability={editor?.kind === 'mcp' ? editor.capability : null}
      tools={editor?.kind === 'mcp' && editor.capability ? mcpTools[editor.capability.id] ?? [] : []}
      secretStatus={secretStatus} saving={saving} error={modalError} onCancel={closeEditor} onSave={save}
      onSaveSecret={saveSecret} onApprove={approve} onTest={testMcp} onSync={syncMcp} />
    <Modal title="导入本地 MCP JSON" open={importOpen} okText="导入" cancelText="取消"
      confirmLoading={importing} onOk={() => void importMcp()} onCancel={() => {
        if (importing) return
        setImportOpen(false); setImportError('')
      }} destroyOnHidden>
      {importError && <Alert type="error" showIcon message={importError} style={{ marginBottom: 16 }} />}
      <Alert type="info" showIcon message="粘贴 .mcp_server_settings.json 内容。headers 和 env 会写入密钥库，已有同名 MCP 会跳过。" style={{ marginBottom: 16 }} />
      <Input.TextArea aria-label="MCP 配置 JSON" value={importText} onChange={(event) => setImportText(event.target.value)}
        rows={12} spellCheck={false} placeholder={'{"mcpServers": {}}'} />
    </Modal>
    <SkillPackageImportModal open={skillPackageImportOpen} onCancel={() => {
      if (!importing) setSkillPackageImportOpen(false)
    }} onImported={() => undefined} onFileSelected={(result, file) => { void handleSkillPackageImported(result, file) }}
      onComplete={(result) => {
        setSkillPackageImportOpen(false)
        setSkillSeed({ name: result.name ?? '', executionPrompt: result.skillMarkdown })
        setEditor({ kind: 'skill', capability: null })
      }} />
  </AdminPage>
}
