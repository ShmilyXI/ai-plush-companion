import { DeleteOutlined, DownloadOutlined, PlusOutlined } from '@ant-design/icons'
import { Alert, Button, Divider, Form, Input, InputNumber, Modal, Select, Space, Switch, Tag, Typography } from 'antd'
import { useEffect, useMemo, useState } from 'react'

import type { Capability, CapabilityRoutePreview, CapabilitySaveInput, SkillTool, SkillTrigger } from '../../api/capabilities'

export interface SkillToolOption {
  key: string
  label: string
  tool: SkillTool
  parameters?: SkillToolParameter[]
}

export interface SkillToolParameter {
  name: string
  label: string
  type: 'string' | 'number' | 'boolean'
}

interface SkillFormValue {
  name: string
  description?: string
  executionPrompt: string
  semanticThreshold: number
  responseMode: 'LLM' | 'FIXED'
  timeoutMs: number
  failureMessage?: string
  triggers: SkillTrigger[]
  toolKeys: string[]
}

function toolKey(tool: SkillTool) {
  return `${tool.toolType}:${tool.toolRefId}:${tool.toolName}`
}

function initialValues(capability: Capability | null, toolOptions: SkillToolOption[]): SkillFormValue {
  const available = new Set(toolOptions.map((item) => item.key))
  return {
    name: capability?.name ?? '',
    description: capability?.description ?? '',
    executionPrompt: capability?.executionPrompt ?? '',
    semanticThreshold: capability?.semanticThreshold ?? 0.7,
    responseMode: capability?.responseMode ?? 'LLM',
    timeoutMs: capability?.timeoutMs ?? 15000,
    failureMessage: capability?.failureMessage ?? '',
    triggers: capability?.triggers.length ? capability.triggers : [
      { type: 'KEYWORD', value: '', priority: 0, caseSensitive: false, enabled: true },
    ],
    toolKeys: capability?.tools.map(toolKey).filter((key) => available.has(key)) ?? [],
  }
}

function initialToolDefaults(capability: Capability | null, toolOptions: SkillToolOption[]) {
  const existing = new Map((capability?.tools ?? []).map((tool) => [toolKey(tool), tool.defaultParams]))
  return Object.fromEntries(toolOptions.map((item) => {
    const saved = existing.get(item.key) ?? item.tool.defaultParams
    const allowed = item.parameters ? new Set(item.parameters.map((parameter) => parameter.name)) : null
    const defaults = Object.fromEntries(Object.entries(saved).filter(([name]) => !allowed || allowed.has(name)))
    item.parameters?.forEach((parameter) => {
      if (!(parameter.name in defaults)) defaults[parameter.name] = ''
    })
    return [item.key, defaults]
  }))
}

export function SkillEditorModal({ open, capability, toolOptions, saving, error, onCancel, onSave, onPreview, onDownload }: {
  open: boolean
  capability: Capability | null
  toolOptions: SkillToolOption[]
  saving: boolean
  error: string
  onCancel: () => void
  onSave: (input: CapabilitySaveInput) => Promise<void> | void
  onPreview?: (deviceId: string, utterance: string) => Promise<CapabilityRoutePreview>
  onDownload?: (capabilityId: string, version: number) => Promise<void> | void
}) {
  const [form] = Form.useForm<SkillFormValue>()
  const [previewDeviceId, setPreviewDeviceId] = useState('')
  const [previewUtterance, setPreviewUtterance] = useState('')
  const [preview, setPreview] = useState<CapabilityRoutePreview | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const [previewError, setPreviewError] = useState('')
  const [toolDefaults, setToolDefaults] = useState<Record<string, Record<string, unknown>>>({})
  const selectedToolKeys = Form.useWatch('toolKeys', form) ?? []
  const watchedValues = Form.useWatch([], form) as Partial<SkillFormValue> | undefined
  const manifestPreview = useMemo(() => {
    const values = watchedValues ?? initialValues(capability, toolOptions)
    const selected = new Map(toolOptions.map((item) => [item.key, item.tool]))
    return {
      schemaVersion: 1,
      id: capability?.id ?? '保存后生成',
      name: values.name?.trim() || '未命名 Skill',
      version: capability?.draftVersion ?? 1,
      runtime: {
        responseMode: values.responseMode ?? 'LLM',
        timeoutMs: values.timeoutMs ?? 15000,
        semanticThreshold: values.semanticThreshold ?? 0.7,
      },
      triggers: values.triggers ?? [],
      tools: (values.toolKeys ?? []).map((key) => {
        const tool = selected.get(key)
        return tool ? { type: tool.toolType, ref: tool.toolRefId, name: tool.toolName, required: tool.required } : { key }
      }),
    }
  }, [capability, toolOptions, watchedValues])

  useEffect(() => {
    if (open) {
      form.setFieldsValue(initialValues(capability, toolOptions))
      setToolDefaults(initialToolDefaults(capability, toolOptions))
      setPreview(null)
      setPreviewError('')
    }
  }, [capability, form, open, toolOptions])

  async function runPreview() {
    if (!onPreview || !previewDeviceId.trim() || !previewUtterance.trim()) return
    setPreviewing(true)
    setPreviewError('')
    try {
      setPreview(await onPreview(previewDeviceId.trim(), previewUtterance.trim()))
    } catch (reason) {
      setPreview(null)
      setPreviewError(reason instanceof Error ? reason.message : '路由预览失败')
    } finally {
      setPreviewing(false)
    }
  }

  async function submit(values: SkillFormValue) {
    const selected = new Map(toolOptions.map((item) => [item.key, item.tool]))
    const tools = values.toolKeys.map((key) => {
      const tool = selected.get(key)
      return tool ? { ...tool, defaultParams: toolDefaults[key] ?? tool.defaultParams } : null
    }).filter((tool): tool is SkillTool => Boolean(tool))
    await onSave({
      type: 'SKILL',
      name: values.name.trim(),
      description: values.description?.trim() || null,
      executionPrompt: values.executionPrompt.trim(),
      semanticThreshold: values.semanticThreshold,
      responseMode: values.responseMode,
      timeoutMs: values.timeoutMs,
      failureMessage: values.failureMessage?.trim() || null,
      triggers: values.triggers.map((trigger) => ({
        ...trigger,
        value: trigger.value.trim(),
        priority: trigger.priority ?? 0,
        caseSensitive: Boolean(trigger.caseSensitive),
        enabled: trigger.enabled !== false,
      })),
      tools,
    })
  }

  return <Modal width={880} title={capability ? '编辑 Skill' : '新建 Skill'} open={open} onCancel={onCancel}
    footer={<Space><Button onClick={onCancel} disabled={saving}>取消</Button><Button type="primary" loading={saving} onClick={() => form.submit()}>保存草稿</Button></Space>}
    closable={!saving} maskClosable={!saving} destroyOnHidden>
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
    {capability?.publishedVersion && <Alert type="info" showIcon style={{ marginBottom: 16 }}
      message={`已发布版本 v${capability.publishedVersion}`}
      description={`编辑后需重新发布，新版本不会覆盖 v${capability.publishedVersion}。`} />}
    <Form form={form} layout="vertical" onFinish={submit} requiredMark="optional">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space align="start" wrap style={{ width: '100%' }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, whitespace: true, message: '请输入 Skill 名称' }]} style={{ minWidth: 280, flex: 1 }}><Input maxLength={128} autoFocus /></Form.Item>
          <Form.Item name="responseMode" label="回复策略" rules={[{ required: true }]} style={{ width: 160 }}><Select options={[{ value: 'LLM', label: '模型组织回复' }, { value: 'FIXED', label: '固定回复' }]} /></Form.Item>
          <Form.Item name="timeoutMs" label="超时毫秒" rules={[{ required: true }]} style={{ width: 160 }}><InputNumber min={1000} max={120000} step={1000} style={{ width: '100%' }} /></Form.Item>
        </Space>
        <Form.Item name="description" label="用途说明"><Input.TextArea rows={2} maxLength={1000} /></Form.Item>
        <Form.Item name="executionPrompt" label="执行提示词" extra="保存后写入分发包根目录的 SKILL.md。" rules={[{ required: true, whitespace: true, message: '请输入执行提示词' }]}><Input.TextArea rows={5} maxLength={10000} /></Form.Item>
        <Space align="start" wrap>
          <Form.Item name="semanticThreshold" label="语义阈值" rules={[{ required: true }]}><InputNumber min={0} max={1} step={0.05} /></Form.Item>
          <Form.Item name="failureMessage" label="失败提示" style={{ minWidth: 360 }}><Input maxLength={500} /></Form.Item>
        </Space>

        <Divider orientation="left" plain>混合触发</Divider>
        <Typography.Text type="secondary">关键词和正则先匹配，正反例用于语义判断。</Typography.Text>
        <Form.List name="triggers">
          {(fields, { add, remove }) => <Space direction="vertical" style={{ width: '100%' }}>
            {fields.map((field) => <Space key={field.key} align="start" wrap style={{ width: '100%' }}>
              <Form.Item name={[field.name, 'type']} label="类型" rules={[{ required: true }]} style={{ width: 180 }}><Select options={[
                { value: 'KEYWORD', label: '关键词' }, { value: 'REGEX', label: '正则' },
                { value: 'POSITIVE_EXAMPLE', label: '正例' }, { value: 'NEGATIVE_EXAMPLE', label: '反例' },
              ]} /></Form.Item>
              <Form.Item name={[field.name, 'value']} label="内容" rules={[{ required: true, whitespace: true, message: '请输入触发内容' }]} style={{ minWidth: 320, flex: 1 }}><Input /></Form.Item>
              <Form.Item name={[field.name, 'priority']} label="优先级"><InputNumber min={-100000} max={100000} /></Form.Item>
              <Form.Item name={[field.name, 'caseSensitive']} label="区分大小写" valuePropName="checked"><Switch /></Form.Item>
              <Form.Item name={[field.name, 'enabled']} label="启用" valuePropName="checked"><Switch /></Form.Item>
              <Button aria-label="删除触发规则" icon={<DeleteOutlined />} danger onClick={() => remove(field.name)} style={{ marginTop: 30 }} />
            </Space>)}
            <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ type: 'KEYWORD', value: '', priority: 0, caseSensitive: false, enabled: true })}>添加触发规则</Button>
          </Space>}
        </Form.List>

        <Divider orientation="left" plain>允许工具</Divider>
        <Form.Item name="toolKeys" label="从已登记工具中选择" rules={[{ required: true, type: 'array', min: 1, message: '至少选择一个工具' }]}>
          <Select mode="multiple" showSearch optionFilterProp="label" placeholder="选择 Plugin、MCP 或设备工具"
            options={toolOptions.map((item) => ({ value: item.key, label: item.label }))} />
        </Form.Item>
        {selectedToolKeys.map((key) => {
          const item = toolOptions.find((option) => option.key === key)
          if (!item?.parameters?.length) return null
          return <Space key={key} direction="vertical" size="small" style={{ width: '100%' }}>
            <Typography.Text strong>{item.label}</Typography.Text>
            <Space align="start" wrap style={{ width: '100%' }}>
              {item.parameters.map((parameter) => {
                const ariaLabel = `${item.label} ${parameter.name}`
                const value = toolDefaults[key]?.[parameter.name]
                const update = (next: unknown) => setToolDefaults((current) => ({
                  ...current,
                  [key]: { ...(current[key] ?? {}), [parameter.name]: next },
                }))
                return <Space key={parameter.name} direction="vertical" size={4} style={{ minWidth: 180, flex: 1 }}>
                  <Typography.Text type="secondary">{parameter.label}</Typography.Text>
                  {parameter.type === 'boolean'
                    ? <Switch aria-label={ariaLabel} checked={value === true} onChange={update} />
                    : parameter.type === 'number'
                      ? <InputNumber aria-label={ariaLabel} value={typeof value === 'number' ? value : null}
                        onChange={(next) => update(next ?? '')} style={{ width: '100%' }} />
                      : <Input aria-label={ariaLabel} value={typeof value === 'string' ? value : ''}
                        onChange={(event) => update(event.target.value)} />}
                </Space>
              })}
            </Space>
          </Space>
        })}

        <Divider orientation="left" plain>分发包</Divider>
        <Space direction="vertical" size="small" style={{ width: '100%' }}>
          {capability?.packageVersion ? <Space wrap>
            <Tag color="green">包 v{capability.packageVersion}</Tag>
            {capability.packageSource && <Tag>{capability.packageSource}</Tag>}
            {capability.packageValidationStatus && <Tag>{capability.packageValidationStatus}</Tag>}
            {capability.packageSha256 && <Typography.Text type="secondary" copyable>{capability.packageSha256}</Typography.Text>}
            {onDownload && <Button icon={<DownloadOutlined />} onClick={() => void onDownload(capability.id, capability.packageVersion!)}>下载 `.skill.zip`</Button>}
          </Space> : <Typography.Text type="secondary">保存草稿或上传包后，这里会显示包版本和摘要。</Typography.Text>}
          <Typography.Text strong>skill.yaml 清单预览</Typography.Text>
          <Input.TextArea aria-label="skill.yaml 清单预览" value={JSON.stringify(manifestPreview, null, 2)} readOnly rows={8} spellCheck={false} />
        </Space>
      </Space>
    </Form>
    {capability && onPreview && <>
      <Divider orientation="left" plain>路由预览</Divider>
      <Typography.Paragraph type="secondary">仅预览路由，不会执行工具。</Typography.Paragraph>
      {previewError && <Alert type="error" showIcon message={previewError} style={{ marginBottom: 12 }} />}
      <Space align="end" wrap style={{ width: '100%' }}>
        <Space direction="vertical" size={4} style={{ minWidth: 220 }}>
          <Typography.Text>设备 ID</Typography.Text>
          <Input aria-label="设备 ID" value={previewDeviceId} onChange={(event) => setPreviewDeviceId(event.target.value)} />
        </Space>
        <Space direction="vertical" size={4} style={{ minWidth: 320, flex: 1 }}>
          <Typography.Text>用户话术</Typography.Text>
          <Input aria-label="用户话术" value={previewUtterance} onChange={(event) => setPreviewUtterance(event.target.value)} />
        </Space>
        <Button loading={previewing} disabled={!previewDeviceId.trim() || !previewUtterance.trim()}
          onClick={() => void runPreview()}>预览路由</Button>
      </Space>
      {preview && <Space direction="vertical" size={4} style={{ marginTop: 12 }}>
        <Typography.Text>规则命中 {preview.deterministicMatches.length ? preview.deterministicMatches.join('、') : '无'}</Typography.Text>
        <Typography.Text>语义候选 {preview.semanticRequired ? preview.eligibleSkillIds.join('、') || '无' : '无需语义判断'}</Typography.Text>
        <Typography.Text>最终 Skill {preview.selectedSkillId ?? '未选中'}</Typography.Text>
        <Typography.Text>允许工具 {preview.allowedTools.length ? preview.allowedTools.join('、') : '无'}</Typography.Text>
      </Space>}
    </>}
  </Modal>
}
