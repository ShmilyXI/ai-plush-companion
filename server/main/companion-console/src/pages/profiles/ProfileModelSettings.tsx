import { Alert, Button, Form, Input, Modal, Select, Space, Typography, message } from 'antd'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'

import { getGlobalModelConfig, listModelCatalog, modelTypes, saveGlobalModelConfig, type ModelCatalogItem, type ModelType } from '../../api/models'
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
  onCredentialsChanged?: () => void
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

export function ProfileModelSettings({ value, options, onChange, onCredentialsChanged }: Props) {
  const bindings = useMemo(() => normalized(value), [value])
  const superAdmin = useAuthStore((state) => state.user?.superAdmin === 1)
  const [credentialModel, setCredentialModel] = useState<ProfileModelOption | null>(null)
  const [credentialCatalog, setCredentialCatalog] = useState<ModelCatalogItem | null>(null)
  const [credentialLoading, setCredentialLoading] = useState(false)
  const [credentialSaving, setCredentialSaving] = useState(false)
  const [credentialForm] = Form.useForm<{ apiUrl?: string; modelId?: string; secrets: Record<string, string> }>()

  function emit(changed: ProfileModelBinding) {
    onChange?.(bindings.map((item) => item.modelType === changed.modelType ? changed : item), changed)
  }

  function changeSelection(modelType: ModelType, nextValue: string) {
    if (nextValue === 'default') return emit({ modelType, source: 'default' })
    const [source, resourceId] = nextValue.split(':')
    if (resourceId && (source === 'global' || source === 'private')) emit({ modelType, source, resourceId })
  }

  async function openCredentialEditor(option: ProfileModelOption) {
    setCredentialModel(option); setCredentialCatalog(null); setCredentialLoading(true)
    credentialForm.resetFields()
    try {
      const catalog = await listModelCatalog(option.modelType, undefined, 'selection')
      setCredentialCatalog(catalog.find((item) => item.source === 'global' && item.id === option.id) ?? null)
      const current = await getGlobalModelConfig(option.id)
      credentialForm.setFieldsValue({ apiUrl: current.apiUrl ?? undefined, modelId: current.modelId ?? undefined, secrets: {} })
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '模型凭据读取失败')
      setCredentialModel(null)
    } finally { setCredentialLoading(false) }
  }

  async function saveCredentials() {
    if (!credentialModel) return
    const values = await credentialForm.validateFields()
    const secrets = Object.fromEntries(Object.entries(values.secrets || {}).filter(([, item]) => item.trim()))
    setCredentialSaving(true)
    try {
      await saveGlobalModelConfig(credentialModel.id, { apiUrl: values.apiUrl, modelId: values.modelId, secrets })
      message.success('模型凭据已更新')
      setCredentialModel(null)
      onCredentialsChanged?.()
    } catch (reason) { message.error(reason instanceof Error ? reason.message : '模型凭据保存失败') }
    finally { setCredentialSaving(false) }
  }

  return <>
  <div className="profile-model-settings">
    <Typography.Paragraph type="secondary">角色默认跟随系统配置，也能选择系统原生模型。</Typography.Paragraph>
    {bindings.map((binding) => {
      const typeOptions = options.filter((item) => item.modelType === binding.modelType)
      const selectableOptions = typeOptions.filter((item) => item.enabled
        && (item.credentialStatus === 'configured' || item.credentialStatus === 'not_required'))
      const selected = selection(binding)
      const selectedOption = typeOptions.find((item) => `${item.source}:${item.id}` === selected)
      const selectedAvailable = selectableOptions.some((item) => `${item.source}:${item.id}` === selected)
      const unavailable = binding.source !== 'default'
        && (binding.enabled === false || !selectedOption || !selectedOption.enabled || selectedOption.credentialStatus === 'missing')
      const unavailableReason = binding.unavailableReason || selectedOption?.unavailableReason || '当前绑定不可用，请重新选择模型'
      const unavailableOption = unavailable && !selectedAvailable
        ? { value: selected, label: selectedOption ? modelOption(selectedOption).label : binding.name || '不可用模型', disabled: true }
        : null
      return <div className="profile-model-row" key={binding.modelType}>
        <div className="profile-model-choice">
          <div className="profile-model-heading">
            <Typography.Text strong>{labels[binding.modelType]}</Typography.Text>
            <Typography.Text type="secondary">{descriptions[binding.modelType]}</Typography.Text>
          </div>
          <Select aria-label={labels[binding.modelType]} value={selected} onChange={(next) => changeSelection(binding.modelType, next)} options={[
            { value: 'default', label: '跟随系统默认' },
            ...(unavailableOption ? [unavailableOption] : []),
            { label: '可用模型', options: selectableOptions.map((item) => ({ ...modelOption(item), value: `${item.source}:${item.id}` })) },
          ]} />
          {unavailable && <Alert type="warning" showIcon message={<Space wrap>
            <span>{unavailableReason}</span>
            {selectedOption?.source === 'global'
              ? <Button type="link" size="small" onClick={() => void openCredentialEditor(selectedOption)}>配置凭据</Button>
              : superAdmin
                ? <Link to="/admin/models">前往模型管理</Link>
                : <Typography.Text type="secondary">请联系管理员处理模型配置</Typography.Text>}
          </Space>} />}
        </div>
      </div>
    })}
  </div>
    <Modal title={`配置${credentialModel?.name || '模型'}凭据`} open={Boolean(credentialModel)} confirmLoading={credentialSaving || credentialLoading}
      okText="保存凭据" cancelText="取消" onCancel={() => setCredentialModel(null)} onOk={() => void saveCredentials()} destroyOnHidden>
      <Form form={credentialForm} layout="vertical">
        <Form.Item label="API 地址" name="apiUrl"><Input /></Form.Item>
        <Form.Item label="模型编号" name="modelId"><Input /></Form.Item>
        {(credentialCatalog?.credentialFields || []).filter((field) => field.secret).map((field) => <Form.Item key={field.key} label={field.label || field.key} name={['secrets', field.key]} rules={field.required ? [{ required: true, message: `请输入${field.label || field.key}` }] : undefined}><Input.Password autoComplete="new-password" /></Form.Item>)}
        <Typography.Text type="secondary">已保存的凭据只显示配置状态，不会回填或出现在角色配置、日志和审计记录中。</Typography.Text>
      </Form>
    </Modal>
  </>
}
