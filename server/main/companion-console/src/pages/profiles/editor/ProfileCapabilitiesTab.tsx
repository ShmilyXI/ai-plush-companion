import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { Button, Card, Descriptions, Form, Input, InputNumber, List, Select, Space, Switch, Tag, Typography } from 'antd'
import type { CompanionBoundDevice, CompanionSkillBinding } from '../../../api/profiles'

type Props = {
  boundDevices?: CompanionBoundDevice[]
  skills?: CompanionSkillBinding[]
  memoryPolicy?: Record<string, unknown>
  onChangeSkills?: (skills: CompanionSkillBinding[]) => void
}

export function ProfileCapabilitiesTab({ boundDevices = [], skills = [], memoryPolicy = {}, onChangeSkills }: Props) {
  const editable = Boolean(onChangeSkills)
  const updateSkill = (index: number, patch: Partial<CompanionSkillBinding>) => {
    onChangeSkills?.(skills.map((skill, current) => current === index ? { ...skill, ...patch } : skill))
  }

  return <>
    <Card className="surface-card" title="设备能力">
    <Form.Item label="使用屏幕表情" name="screenExpressionEnabled" valuePropName="checked"><Switch /></Form.Item>
    <Form.Item label="允许相机偏好" name="cameraPreferenceEnabled" valuePropName="checked"><Switch /></Form.Item>
    <Typography.Title level={5}>绑定设备</Typography.Title>
    <List size="small" locale={{ emptyText: '暂无绑定设备' }} dataSource={boundDevices} renderItem={(device) => <List.Item>
      <List.Item.Meta title={device.alias || device.macAddress} description={`${device.board || '未知板型'} · ${device.appVersion || '未知版本'}`} />
      <Tag color={device.online ? 'green' : 'default'}>{device.online ? '在线' : '离线'}</Tag>
    </List.Item>} />
    <Space align="center" style={{ width: '100%', justifyContent: 'space-between' }}>
      <Typography.Title level={5} style={{ margin: 0 }}>{editable ? 'Agent Skill 草稿' : '已发布 Skill'}</Typography.Title>
      {editable && <Button type="dashed" icon={<PlusOutlined />} onClick={() => onChangeSkills?.([
        ...skills,
        { skillId: '', versionMode: 'LATEST', fixedVersion: null, overrideJson: null, triggerPriority: 0, enabled: true },
      ])}>添加 Skill</Button>}
    </Space>
    <List size="small" locale={{ emptyText: '暂无已发布 Skill' }} dataSource={skills} renderItem={(skill) => <List.Item>
      {editable ? <Space wrap style={{ width: '100%' }} align="start">
        <Input aria-label="Skill 编号" placeholder="Skill 编号" value={skill.skillId}
          onChange={(event) => updateSkill(skills.indexOf(skill), { skillId: event.target.value })} style={{ width: 180 }} />
        <Select aria-label="Skill 版本策略" value={skill.versionMode || 'LATEST'} style={{ width: 130 }}
          options={[{ value: 'LATEST', label: '跟随最新版本' }, { value: 'FIXED', label: '固定版本' }]}
          onChange={(versionMode) => updateSkill(skills.indexOf(skill), { versionMode, fixedVersion: versionMode === 'LATEST' ? null : (skill.fixedVersion || 1) })} />
        {skill.versionMode === 'FIXED' && <InputNumber aria-label="固定 Skill 版本" min={1} value={skill.fixedVersion || 1}
          onChange={(fixedVersion) => updateSkill(skills.indexOf(skill), { fixedVersion: fixedVersion || 1 })} />}
        <InputNumber aria-label="Skill 触发优先级" value={skill.triggerPriority || 0}
          onChange={(triggerPriority) => updateSkill(skills.indexOf(skill), { triggerPriority: triggerPriority || 0 })} />
        <Switch aria-label="启用 Skill" checked={skill.enabled} onChange={(enabled) => updateSkill(skills.indexOf(skill), { enabled })} />
        <Button aria-label={`删除 Skill ${skill.skillId || '草稿'}`} danger type="text" icon={<DeleteOutlined />}
          onClick={() => onChangeSkills?.(skills.filter((current) => current !== skill))} />
      </Space> : <><List.Item.Meta title={skill.skillId} description={`${skill.versionMode || 'LATEST'}${skill.fixedVersion ? ` · v${skill.fixedVersion}` : ''}`} />
        <Tag>{skill.enabled ? '已启用' : '已停用'}</Tag></>}
    </List.Item>} />
    </Card>
    <Card className="surface-card" title="记忆策略">
      <Descriptions size="small" column={1} items={Object.entries(memoryPolicy).map(([key, value]) => ({ key, label: key, children: String(value) }))} />
    </Card>
  </>
}
