import { Alert, Card, Descriptions, Skeleton, Space, Tag, Typography } from 'antd'
import { useEffect, useMemo, useState } from 'react'

import {
  listDeviceSkillCatalog, listDeviceSkills,
  type DeviceSkillBinding, type DeviceSkillCatalogItem,
} from '../../api/capabilities'

export function DeviceSkillCard({ deviceId }: { deviceId: string }) {
  const [catalog, setCatalog] = useState<DeviceSkillCatalogItem[]>([])
  const [bindings, setBindings] = useState<DeviceSkillBinding[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    void Promise.all([
      listDeviceSkillCatalog(deviceId, { signal: controller.signal }),
      listDeviceSkills(deviceId, { signal: controller.signal }),
    ]).then(([nextCatalog, nextBindings]) => {
      if (controller.signal.aborted) return
      setCatalog(nextCatalog)
      setBindings(nextBindings)
    }).catch((reason) => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '设备能力加载失败')
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false)
    })
    return () => controller.abort()
  }, [deviceId])

  const bindingBySkill = useMemo(() => new Map(bindings.map((binding) => [binding.skillId, binding])), [bindings])
  const activeCount = bindings.filter((binding) => binding.enabled).length

  return <Card title="有效设备能力" className="surface-card device-skill-card"
    extra={<Tag color={activeCount ? 'blue' : 'default'}>{activeCount} 个已启用</Tag>}>
    <Typography.Paragraph type="secondary">
      Skill 由智能体的已激活版本统一管理。此处只展示当前设备硬件过滤后的有效结果。
    </Typography.Paragraph>
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
    {loading ? <Skeleton active paragraph={{ rows: 4 }} /> : <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {catalog.length === 0 && <Typography.Text type="secondary">暂无可用的已发布 Skill</Typography.Text>}
      {catalog.map((item) => {
        const binding = bindingBySkill.get(item.skillId)
        const unavailable = !item.available
        return <Card key={item.skillId} size="small" type="inner" title={<Space wrap>
          <Typography.Text strong>{item.name}</Typography.Text>
          <Tag>{`版本 v${binding?.resolvedVersion ?? item.publishedVersion}`}</Tag>
          {binding?.enabled && <Tag color="green">已启用</Tag>}
          {!binding?.enabled && !unavailable && <Tag>未启用</Tag>}
          {unavailable && <Tag color="orange">不可用</Tag>}
        </Space>}>
          {item.description && <Typography.Paragraph>{item.description}</Typography.Paragraph>}
          {unavailable && <Alert type="warning" showIcon message={item.unavailableReason ?? '当前设备不可用'} />}
          {binding && <Descriptions size="small" column={1} style={{ marginTop: 12 }}>
            <Descriptions.Item label="版本策略">{binding.versionMode === 'FIXED' ? `固定 v${binding.fixedVersion}` : '跟随智能体版本'}</Descriptions.Item>
            <Descriptions.Item label="触发优先级">{binding.triggerPriority}</Descriptions.Item>
            <Descriptions.Item label="设备覆盖参数">{Object.keys(binding.overrides).length ? JSON.stringify(binding.overrides) : '无'}</Descriptions.Item>
          </Descriptions>}
        </Card>
      })}
    </Space>}
  </Card>
}
