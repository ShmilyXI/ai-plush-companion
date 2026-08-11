import { render, screen } from '@testing-library/react'
import { Form } from 'antd'
import { describe, expect, it } from 'vitest'

import type { ModelProviderField, ModelType } from '../../api/xiaozhiModels'
import { ModelFieldEditor } from './ModelFieldEditor'

const fields: ModelProviderField[] = [
  { key: 'name', label: '模型名称', type: 'string' },
  { key: 'temperature', label: '温度', type: 'float', default: 0.5 },
  { key: 'max_tokens', label: '最大令牌数', type: 'integer' },
  { key: 'top_p', label: 'top_p值', type: 'float' },
  { key: 'top_k', label: 'top_k值', type: 'integer' },
  { key: 'frequency_penalty', label: '频率惩罚', type: 'float' },
  { key: 'stream', label: '流式输出', type: 'boolean', default: true },
  { key: 'language', label: '语言', type: 'string', options: ['zh', 'en'], default: 'zh' },
  { key: 'headers', label: '请求头', type: 'dict', default: {} },
  { key: 'api_key', label: 'API Key', type: 'string' },
]

function Harness({ modelType = 'LLM' }: { modelType?: ModelType }) {
  return <Form initialValues={{
    configJson: { temperature: 0.5, stream: true, language: 'zh', headers: '{}' },
  }}>
    <ModelFieldEditor modelType={modelType} fields={fields} configuredSecretPaths={new Set(['api_key'])} />
  </Form>
}

describe('ModelFieldEditor', () => {
  it('renders every native provider field type', () => {
    render(<Harness />)

    expect(screen.getByLabelText('模型名称')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('温度')).toHaveAttribute('role', 'spinbutton')
    expect(screen.getByRole('switch', { name: '流式输出' })).toBeChecked()
    expect(screen.getByRole('combobox', { name: '语言' })).toBeInTheDocument()
    expect(screen.getByLabelText('请求头')).toHaveValue('{}')
    expect(screen.getByLabelText('API Key')).toHaveAttribute('type', 'password')
  })

  it('shows saved credential state without putting the secret into the input', () => {
    render(<Harness />)

    expect(screen.getByLabelText('API Key')).toHaveValue('')
    expect(screen.getByText('API Key 已配置，留空会保留原值')).toBeInTheDocument()
  })

  it('explains LLM sampling controls in plain language', () => {
    render(<Harness />)

    expect(screen.getByText(/调低会更稳/)).toBeInTheDocument()
    expect(screen.getByText(/太低可能说到一半被截断/)).toBeInTheDocument()
    expect(screen.getByText(/通常只调温度/)).toBeInTheDocument()
    expect(screen.getByText(/常见起点是 40/)).toBeInTheDocument()
    expect(screen.getByText(/建议 0。/)).toBeInTheDocument()
    expect(screen.getByLabelText('top_k值')).toHaveAttribute('placeholder', '常见 40，不确定请留空')
  })

  it('does not reuse LLM guidance for TTS fields with the same names', () => {
    render(<Harness modelType="TTS" />)

    expect(screen.queryByText(/调低会更稳/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('top_k值')).not.toHaveAttribute('placeholder')
  })
})
