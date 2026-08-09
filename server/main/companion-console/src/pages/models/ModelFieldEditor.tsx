import { Form, Input, InputNumber, Select, Switch, Typography } from 'antd'

import type { ModelProviderField } from '../../api/xiaozhiModels'

interface ModelFieldEditorProps {
  fields: ModelProviderField[]
  configuredSecretPaths?: Set<string>
}

function option(value: unknown) {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const item = value as Record<string, unknown>
    const rawValue = item.value ?? item.key ?? item.label
    return { value: rawValue, label: String(item.label ?? rawValue ?? '') }
  }
  return { value, label: String(value) }
}

function dictValidator(_: unknown, value: unknown) {
  if (value === undefined || value === null || value === '') return Promise.resolve()
  try {
    const parsed: unknown = typeof value === 'string' ? JSON.parse(value) : value
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? Promise.resolve()
      : Promise.reject(new Error('请输入 JSON 对象'))
  } catch {
    return Promise.reject(new Error('请输入有效的 JSON 对象'))
  }
}

function isNumeric(type: ModelProviderField['type']) {
  return ['number', 'integer', 'int', 'float'].includes(type)
}

function isCredentialKey(key: string) {
  const normalized = key.replace(/([a-z0-9])([A-Z])/g, '$1_$2').replace(/[^a-zA-Z0-9]+/g, '_').toLowerCase()
  return /(^|_)(token|secret|password|authorization|credential)($|_)/.test(normalized)
    || normalized.includes('api_key')
    || normalized.includes('access_key_secret')
    || normalized.includes('private_key')
}

export function ModelFieldEditor({ fields, configuredSecretPaths = new Set() }: ModelFieldEditorProps) {
  return <>
    {fields.map((field) => {
      const credential = field.type === 'password' || isCredentialKey(field.key)
      const savedSecret = credential && configuredSecretPaths.has(field.key)
      const rules = field.type === 'dict' ? [{ validator: dictValidator }] : undefined
      let control
      if (field.options?.length) {
        control = <Select aria-label={field.label} options={field.options.map(option)} />
      } else if (isNumeric(field.type)) {
        control = <InputNumber aria-label={field.label} style={{ width: '100%' }} />
      } else if (field.type === 'boolean') {
        control = <Switch aria-label={field.label} />
      } else if (field.type === 'dict') {
        control = <Input.TextArea aria-label={field.label} rows={4} spellCheck={false} />
      } else if (credential) {
        control = <Input.Password aria-label={field.label} autoComplete="new-password" />
      } else {
        control = <Input aria-label={field.label} />
      }
      return <Form.Item
        key={field.key}
        name={['configJson', field.key]}
        label={field.label}
        valuePropName={field.type === 'boolean' ? 'checked' : 'value'}
        rules={rules}
        extra={savedSecret
          ? <Typography.Text type="secondary">{field.label} 已配置，留空会保留原值</Typography.Text>
          : undefined}
      >{control}</Form.Item>
    })}
  </>
}
