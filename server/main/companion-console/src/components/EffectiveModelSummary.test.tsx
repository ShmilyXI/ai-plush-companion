import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { EffectiveModelSummary } from './EffectiveModelSummary'

describe('EffectiveModelSummary', () => {
  it('shows the actual model source and role override state', () => {
    render(<EffectiveModelSummary models={[
      { modelType: 'LLM', resourceId: 'p1', name: '我的 Ollama', source: 'private', modelId: 'qwen', overridden: true, overrides: {} },
    ]} />)

    expect(screen.getByText('我的 Ollama')).toBeInTheDocument()
    expect(screen.getByText('我的私有模型')).toBeInTheDocument()
    expect(screen.getByText('角色参数已覆盖')).toBeInTheDocument()
    expect(screen.queryByText('qwen')).not.toBeInTheDocument()
  })
})
