import { List, Space, Tag, Typography } from 'antd'

import { modelTypes, type ModelType } from '../api/models'

export interface EffectiveModelItem {
  modelType: ModelType
  resourceId: string | null
  name: string
  source: 'default' | 'global' | 'private' | 'legacy'
  modelId: string | null
  overridden: boolean
  overrides: Record<string, unknown>
}

const sourceLabels: Record<EffectiveModelItem['source'], string> = {
  default: '全局默认', global: '管理员资源', private: '我的私有模型', legacy: '兼容配置',
}

export function EffectiveModelSummary({ models }: { models: EffectiveModelItem[] }) {
  const rows = modelTypes.map((modelType) => ({ modelType, model: models.find((item) => item.modelType === modelType) }))
  return <List className="effective-model-summary" dataSource={rows} renderItem={({ modelType, model }) => <List.Item>
    <List.Item.Meta title={modelType} description={model?.name || '未配置'} />
    <Space wrap>
      {model && <Tag>{sourceLabels[model.source]}</Tag>}
      {model?.overridden && <Tag color="cyan">角色参数已覆盖</Tag>}
      {!model && <Typography.Text type="secondary">等待系统配置</Typography.Text>}
    </Space>
  </List.Item>} />
}
