import { UndoOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Form, Input, Radio } from 'antd'

type ProfileBasicsTabProps = {
  saving: boolean
  restoring: boolean
  onRestorePrompt: () => void
}

export function ProfileBasicsTab({ saving, restoring, onRestorePrompt }: ProfileBasicsTabProps) {
  return <div className="profile-editor-grid">
    <Card className="surface-card" title="日常设置">
      <Form.Item label="角色名称" name="name" rules={[{ required: true, whitespace: true, message: '请输入角色名称' }, { max: 64 }]}><Input maxLength={64} /></Form.Item>
      <Form.Item label="陪伴关系" name="relationMode"><Radio.Group optionType="button" options={[{ label: '治愈型朋友', value: 'friend' }, { label: '治愈型恋人', value: 'lover' }]} /></Form.Item>
      <Form.Item label="怎么称呼你" name="userAddress" rules={[{ max: 64 }]}><Input maxLength={64} placeholder="例如 小夏" /></Form.Item>
      <Form.Item label="性格" name="personality" rules={[{ max: 1000 }]}><Input.TextArea rows={4} maxLength={1000} showCount /></Form.Item>
    </Card>
    <Card className="surface-card advanced-prompt-card" title="高级提示词" extra={<Button aria-label="恢复初始提示词" icon={<UndoOutlined />} loading={restoring}
      disabled={saving || restoring} onClick={onRestorePrompt}>恢复初始提示词</Button>}>
      <Alert type="info" showIcon message="这里决定角色完整的说话方式和行为边界。恢复操作只影响此处。" />
      <Form.Item label="完整提示词" name="systemPrompt" rules={[{ max: 16000 }]}><Input.TextArea rows={18} maxLength={16000} showCount /></Form.Item>
    </Card>
  </div>
}
