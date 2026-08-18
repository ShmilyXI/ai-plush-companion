import { Alert, Button, Card, Checkbox, Input, InputNumber, Select, Skeleton, Space, Switch, Tag, Typography } from 'antd'
import { useEffect, useMemo, useState } from 'react'

import {
  listDeviceSkillCatalog, listDeviceSkills, saveDeviceSkills,
  type DeviceSkillBinding, type DeviceSkillBindingInput, type DeviceSkillCatalogItem,
  type DeviceSkillVersionMode,
} from '../../api/capabilities'

interface SkillDraft {
  bound: boolean
  versionMode: DeviceSkillVersionMode
  fixedVersion: number | null
  enabled: boolean
  overrides: Record<string, string>
  triggerPriority: number
}

function displayValue(value: unknown) {
  if (typeof value === 'string') return value
  if (value === undefined) return ''
  return JSON.stringify(value)
}

function draftFor(catalog: DeviceSkillCatalogItem, binding?: DeviceSkillBinding): SkillDraft {
  const values = binding?.overrides ?? {}
  const overrides: Record<string, string> = {}
  catalog.overridableFields.forEach((field) => {
    overrides[field] = displayValue(values[field] ?? catalog.defaults[field])
  })
  return {
    bound: Boolean(binding),
    versionMode: binding?.versionMode ?? 'LATEST',
    fixedVersion: binding?.fixedVersion ?? catalog.versions.at(-1) ?? catalog.publishedVersion,
    enabled: binding?.enabled ?? true,
    overrides,
    triggerPriority: binding?.triggerPriority ?? 0,
  }
}

function parseOverride(raw: string, sample: unknown) {
  if (typeof sample === 'string' || sample === undefined) return raw
  try {
    return JSON.parse(raw) as unknown
  } catch {
    throw new Error('覆盖参数必须与默认值类型一致')
  }
}

export function DeviceSkillCard({ deviceId }: { deviceId: string }) {
  const [catalog, setCatalog] = useState<DeviceSkillCatalogItem[]>([])
  const [drafts, setDrafts] = useState<Record<string, SkillDraft>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    setSaved(false)
    void Promise.all([
      listDeviceSkillCatalog(deviceId, { signal: controller.signal }),
      listDeviceSkills(deviceId, { signal: controller.signal }),
    ]).then(([nextCatalog, bindings]) => {
      if (controller.signal.aborted) return
      const bySkill = new Map(bindings.map((binding) => [binding.skillId, binding]))
      setCatalog(nextCatalog)
      setDrafts(Object.fromEntries(nextCatalog.map((item) => [item.skillId, draftFor(item, bySkill.get(item.skillId))])))
    }).catch((reason) => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '设备能力加载失败')
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false)
    })
    return () => controller.abort()
  }, [deviceId])

  const boundCount = useMemo(() => Object.values(drafts).filter((draft) => draft.bound).length, [drafts])

  function update(skillId: string, patch: Partial<SkillDraft>) {
    setSaved(false)
    setDrafts((current) => ({ ...current, [skillId]: { ...current[skillId], ...patch } }))
  }

  function updateOverride(skillId: string, field: string, value: string) {
    setSaved(false)
    setDrafts((current) => ({
      ...current,
      [skillId]: { ...current[skillId], overrides: { ...current[skillId].overrides, [field]: value } },
    }))
  }

  async function save() {
    setSaving(true)
    setError('')
    setSaved(false)
    try {
      const bindings: DeviceSkillBindingInput[] = catalog.flatMap((item) => {
        const draft = drafts[item.skillId]
        if (!draft?.bound) return []
        const overrides = Object.fromEntries(item.overridableFields.map((field) => [
          field,
          parseOverride(draft.overrides[field] ?? '', item.defaults[field]),
        ]))
        return [{
          skillId: item.skillId,
          versionMode: draft.versionMode,
          fixedVersion: draft.versionMode === 'FIXED' ? draft.fixedVersion : null,
          enabled: draft.enabled,
          overrides,
          triggerPriority: draft.triggerPriority,
        }]
      })
      const savedBindings = await saveDeviceSkills(deviceId, bindings)
      const savedBySkill = new Map(savedBindings.map((binding) => [binding.skillId, binding]))
      setDrafts(Object.fromEntries(catalog.map((item) => [item.skillId, draftFor(item, savedBySkill.get(item.skillId))])))
      setSaved(true)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '设备能力保存失败')
    } finally {
      setSaving(false)
    }
  }

  return <Card title="设备能力" className="surface-card device-skill-card"
    extra={<Tag color={boundCount ? 'blue' : 'default'}>{boundCount} 个已绑定</Tag>}>
    <Typography.Paragraph type="secondary">Skill 跟随设备。切换陪伴角色不会改变这里的绑定。</Typography.Paragraph>
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
    {saved && <Alert type="success" showIcon message="设备能力已保存" style={{ marginBottom: 12 }} />}
    {loading ? <Skeleton active paragraph={{ rows: 4 }} /> : <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {catalog.length === 0 && <Typography.Text type="secondary">暂无可绑定的已发布 Skill</Typography.Text>}
      {catalog.map((item) => {
        const draft = drafts[item.skillId]
        if (!draft) return null
        const controlsDisabled = !draft.bound || !item.available
        return <Card key={item.skillId} size="small" type="inner" title={<Space wrap>
          <Checkbox aria-label={`绑定 ${item.name}`} checked={draft.bound}
            disabled={!item.available && !draft.bound}
            onChange={(event) => update(item.skillId, { bound: event.target.checked })}>绑定 {item.name}</Checkbox>
          <Tag>{`最新 v${item.publishedVersion}`}</Tag>
          {item.packageSource && <Tag color="green">包 {item.packageSource}</Tag>}
        </Space>}>
          {item.description && <Typography.Paragraph>{item.description}</Typography.Paragraph>}
          {!item.available && <Alert type="warning" showIcon message={item.unavailableReason ?? '当前设备不可用'} style={{ marginBottom: 12 }} />}
          <Space align="start" wrap style={{ width: '100%' }}>
            <Space direction="vertical" size={4}>
              <Typography.Text>启用</Typography.Text>
              <Switch aria-label={`启用 ${item.name}`} checked={draft.enabled} disabled={controlsDisabled}
                onChange={(enabled) => update(item.skillId, { enabled })} />
            </Space>
            <Space direction="vertical" size={4}>
              <Typography.Text>版本策略</Typography.Text>
              <Select aria-label={`${item.name}版本策略`} value={draft.versionMode} disabled={controlsDisabled}
                style={{ width: 150 }} onChange={(versionMode) => update(item.skillId, { versionMode })}
                options={[{ value: 'LATEST', label: '跟随最新' }, { value: 'FIXED', label: '固定版本' }]} />
            </Space>
            {draft.versionMode === 'FIXED' && <Space direction="vertical" size={4}>
              <Typography.Text>固定版本</Typography.Text>
              <Select aria-label={`${item.name}固定版本`} value={draft.fixedVersion ?? undefined} disabled={controlsDisabled}
                style={{ width: 110 }} onChange={(fixedVersion) => update(item.skillId, { fixedVersion })}
                options={item.versions.map((version) => ({ value: version, label: `v${version}` }))} />
            </Space>}
            <Space direction="vertical" size={4}>
              <Typography.Text>触发优先级</Typography.Text>
              <InputNumber aria-label={`${item.name}触发优先级`} value={draft.triggerPriority} disabled={controlsDisabled}
                min={-100000} max={100000} onChange={(value) => update(item.skillId, { triggerPriority: value ?? 0 })} />
            </Space>
          </Space>
          {item.overridableFields.length > 0 && <Space direction="vertical" size={8} style={{ width: '100%', marginTop: 12 }}>
            <Typography.Text strong>设备覆盖参数</Typography.Text>
            <Space wrap>{item.overridableFields.map((field) => <Space direction="vertical" size={4} key={field}>
              <Typography.Text>{field}</Typography.Text>
              <Input aria-label={`${item.name} ${field}`} value={draft.overrides[field] ?? ''} disabled={controlsDisabled}
                onChange={(event) => updateOverride(item.skillId, field, event.target.value)} />
            </Space>)}</Space>
          </Space>}
        </Card>
      })}
      <Button type="primary" loading={saving} onClick={() => void save()}>保存设备能力</Button>
    </Space>}
  </Card>
}
