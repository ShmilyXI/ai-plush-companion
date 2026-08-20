import { HistoryOutlined } from '@ant-design/icons'
import { Button, Card, List, Space } from 'antd'

import type { ProfileVersion } from '../../../api/profiles'

function sourceLabel(source: string) {
  if (source === 'initial') return '初始版本'
  if (source === 'companion-restore-prompt') return '恢复初始提示词'
  if (source === 'companion-update') return '角色设置更新'
  return '配置记录'
}

type ProfileVersionsTabProps = {
  versions: ProfileVersion[]
  hasMore: boolean
  loadingMore: boolean
  onLoadMore: () => void
  onRestore: (version: ProfileVersion) => void
  onActivate: (version: ProfileVersion) => void
  onPublish: () => void
  activeVersionNo?: number | null
  restoringVersionId?: string
  activatingVersionId?: string
  publishing?: boolean
}

export function ProfileVersionsTab({ versions, hasMore, loadingMore, onLoadMore, onRestore, onActivate, onPublish, activeVersionNo, restoringVersionId, activatingVersionId, publishing }: ProfileVersionsTabProps) {
  return <Card className="surface-card version-card" title={<Space><HistoryOutlined />版本记录</Space>}>
    <Button type="primary" block loading={publishing} disabled={Boolean(restoringVersionId) || Boolean(activatingVersionId)} onClick={onPublish}>发布当前草稿</Button>
    <List dataSource={versions} locale={{ emptyText: '暂无版本记录' }} renderItem={(version) => <List.Item actions={[
      <Button key="activate" type="link" loading={activatingVersionId === version.id} disabled={Boolean(restoringVersionId) || Boolean(activatingVersionId) || activeVersionNo === version.versionNo}
        aria-label={`激活版本 ${version.versionNo}`} onClick={() => onActivate(version)}>{activeVersionNo === version.versionNo ? '当前激活' : '激活'}</Button>,
      <Button key="restore" type="link" loading={restoringVersionId === version.id} disabled={Boolean(restoringVersionId) || Boolean(activatingVersionId)}
        aria-label={`恢复版本 ${version.versionNo}`} onClick={() => onRestore(version)}>恢复到草稿</Button>,
    ]}><List.Item.Meta title={`版本 ${version.versionNo} · ${sourceLabel(version.source)}`} description={new Date(version.createdAt).toLocaleString('zh-CN')} /></List.Item>} />
    {hasMore && <Button block loading={loadingMore} onClick={onLoadMore}>加载更多版本</Button>}
  </Card>
}
