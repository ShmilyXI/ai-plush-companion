import { Alert, Select, Space, Typography } from 'antd'
import { useMemo } from 'react'
import { Link } from 'react-router-dom'

import { modelTypes, type ModelType } from '../../api/models'
import type { ProfileModelBinding, ProfileModelOption } from '../../api/profiles'
import { useAuthStore } from '../../auth/authStore'

const labels: Record<ModelType, string> = {
  LLM: '对话模型 LLM', ASR: '语音识别 ASR', TTS: '语音合成 TTS',
  VAD: '声音活动检测 VAD', VLLM: '视觉理解 VLLM', Memory: '长期记忆 Memory',
}

const descriptions: Record<ModelType, string> = {
  LLM: '负责理解问题并生成回复', ASR: '把用户说的话转换成文字',
  TTS: '把机器人回复转换成声音', VAD: '判断用户何时开始和结束说话',
  VLLM: '理解摄像头拍到的图片内容', Memory: '保存并检索长期对话记忆',
}

interface Props {
  value?: ProfileModelBinding[]
  options: ProfileModelOption[]
  onChange?: (value: ProfileModelBinding[], changed: ProfileModelBinding) => void
}

function normalized(value: ProfileModelBinding[] = []) {
  return modelTypes.map((modelType) => value.find((item) => item.modelType === modelType)
    ?? { modelType, source: 'default' as const })
}

function selection(binding: ProfileModelBinding) {
  return binding.source === 'default' ? 'default' : `${binding.source}:${binding.resourceId}`
}

function modelOption(item: ProfileModelOption) {
  const missing = item.credentialStatus === 'missing' ? ' · 未配置' : ''
  return {
    value: `${item.source}:${item.id}`,
    label: `${item.name} · ${item.vendorName || item.providerCode}${missing}`,
    disabled: !item.enabled || item.credentialStatus === 'missing',
  }
}

export function ProfileModelSettings({ value, options, onChange }: Props) {
  const bindings = useMemo(() => normalized(value), [value])
  const superAdmin = useAuthStore((state) => state.user?.superAdmin === 1)

  function emit(changed: ProfileModelBinding) {
    onChange?.(bindings.map((item) => item.modelType === changed.modelType ? changed : item), changed)
  }

  function changeSelection(modelType: ModelType, nextValue: string) {
    if (nextValue === 'default') return emit({ modelType, source: 'default' })
    if (!nextValue.startsWith('global:')) return
    const resourceId = nextValue.slice('global:'.length)
    if (resourceId) emit({ modelType, source: 'global', resourceId })
  }

  return <div className="profile-model-settings">
    <Typography.Paragraph type="secondary">角色默认跟随系统配置，也能选择系统原生模型。</Typography.Paragraph>
    {bindings.map((binding) => {
      const globalOptions = options.filter((item) => item.modelType === binding.modelType && item.source === 'global')
      const selected = selection(binding)
      const selectedOption = globalOptions.find((item) => `global:${item.id}` === selected)
      const unavailable = binding.source !== 'default'
        && (binding.enabled === false || !selectedOption || !selectedOption.enabled || selectedOption.credentialStatus === 'missing')
      const unavailableReason = binding.unavailableReason || selectedOption?.unavailableReason || '当前绑定不可用，请重新选择模型'
      return <div className="profile-model-row" key={binding.modelType}>
        <div className="profile-model-choice">
          <div className="profile-model-heading">
            <Typography.Text strong>{labels[binding.modelType]}</Typography.Text>
            <Typography.Text type="secondary">{descriptions[binding.modelType]}</Typography.Text>
          </div>
          <Select aria-label={labels[binding.modelType]} value={selected} onChange={(next) => changeSelection(binding.modelType, next)} options={[
            { value: 'default', label: '跟随系统默认' },
            ...(unavailable && !selectedOption ? [{ value: selected, label: binding.name || '不可用模型', disabled: true }] : []),
            { label: '系统模型', options: globalOptions.map(modelOption) },
          ]} />
          {unavailable && <Alert type="warning" showIcon message={<Space wrap>
            <span>{unavailableReason}</span>
            {superAdmin
              ? <Link to="/admin/models">前往模型管理</Link>
              : <Typography.Text type="secondary">请联系管理员处理模型配置</Typography.Text>}
          </Space>} />}
        </div>
      </div>
    })}
  </div>
}
