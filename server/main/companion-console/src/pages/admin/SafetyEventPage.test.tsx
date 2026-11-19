import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as safetyApi from '../../api/safetyEvents'
import { SafetyEventPage } from './SafetyEventPage'

vi.mock('../../api/safetyEvents', async () => {
  const actual = await vi.importActual<typeof import('../../api/safetyEvents')>('../../api/safetyEvents')
  return { ...actual, listSafetyEvents: vi.fn() }
})

describe('SafetyEventPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('describes each event by category, where it was caught and what was done', async () => {
    vi.mocked(safetyApi.listSafetyEvents).mockResolvedValue({
      total: 3,
      list: [
        { id: '3', userId: '9', agentId: 'a', direction: 'input', action: 'crisis', category: 'self_harm', createdAt: '2026-11-20T10:00:00' },
        { id: '2', userId: '9', agentId: 'a', direction: 'output', action: 'block', category: 'sexual_explicit', createdAt: '2026-11-20T09:00:00' },
        { id: '1', userId: '7', agentId: 'a', direction: 'input', action: 'block', category: 'new_category', createdAt: null },
      ],
    })

    render(<SafetyEventPage />)

    const crisis = (await screen.findByText('自伤风险')).closest('tr') as HTMLElement
    expect(within(crisis).getByText('用户输入')).toBeInTheDocument()
    expect(within(crisis).getByText('给出求助信息')).toBeInTheDocument()
    const withdrawn = screen.getByText('露骨的性内容').closest('tr') as HTMLElement
    expect(within(withdrawn).getByText('模型回复')).toBeInTheDocument()
    expect(within(withdrawn).getByText('收回回复')).toBeInTheDocument()
    // 以后新增的类别即使没有中文名，也照原样显示出来。
    const unknown = screen.getByText('new_category').closest('tr') as HTMLElement
    expect(within(unknown).getByText('拒绝回应')).toBeInTheDocument()
  })

  it('reports a loading failure with a retry', async () => {
    vi.mocked(safetyApi.listSafetyEvents).mockRejectedValue(new Error('服务暂时不可用'))

    render(<SafetyEventPage />)

    expect(await screen.findByText('服务暂时不可用')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument()
  })
})
