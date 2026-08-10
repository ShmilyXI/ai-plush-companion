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
  restoringVersionId?: string
}

export function ProfileVersionsTab({ versions, hasMore, loadingMore, onLoadMore, onRestore, restoringVersionId }: ProfileVersionsTabProps) {
  return <Card className="surface-card version-card" title={<Space><HistoryOutlined />版本记录</Space>}>
    <List dataSource={versions} locale={{ emptyText: '暂无版本记录' }} renderItem={(version) => <List.Item actions={[
      <Button key="restore" type="link" loading={restoringVersionId === version.id} disabled={Boolean(restoringVersionId)}
        aria-label={`恢复版本 ${version.versionNo}`} onClick={() => onRestore(version)}>恢复</Button>,
    ]}><List.Item.Meta title={`版本 ${version.versionNo} · ${sourceLabel(version.source)}`} description={new Date(version.createdAt).toLocaleString('zh-CN')} /></List.Item>} />
    {hasMore && <Button block loading={loadingMore} onClick={onLoadMore}>加载更多版本</Button>}
  </Card>
}
