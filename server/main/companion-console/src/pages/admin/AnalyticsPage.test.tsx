import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as analyticsApi from '../../api/analytics'
import { AnalyticsPage } from './AnalyticsPage'

vi.mock('../../api/analytics', async () => {
  const actual = await vi.importActual<typeof import('../../api/analytics')>('../../api/analytics')
  return { ...actual, getAnalyticsOverview: vi.fn() }
})

describe('AnalyticsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows retention as people and share, and marks days that have not arrived yet', async () => {
    vi.mocked(analyticsApi.getAnalyticsOverview).mockResolvedValue({
      cohorts: [
        { date: '2026-11-19', size: 4, activated: 3, d1: null, d7: null, d30: null },
        { date: '2026-11-10', size: 20, activated: 18, d1: 12, d7: 9, d30: null },
      ],
      latency: [{ date: '2026-11-20', turns: 120, p50: 820, p90: 2100 }],
      events: [
        { date: '2026-11-20', name: 'mic_failed', total: 4, users: 2 },
        { date: '2026-11-20', name: 'something_new', total: 1, users: 1 },
      ],
    })

    render(<AnalyticsPage />)

    const older = (await screen.findByText('2026-11-10')).closest('tr') as HTMLElement
    expect(within(older).getByText('18（90%）')).toBeInTheDocument()
    expect(within(older).getByText('12（60%）')).toBeInTheDocument()
    expect(within(older).getByText('9（45%）')).toBeInTheDocument()
    expect(within(older).getByText('未到')).toBeInTheDocument()
    const recent = screen.getByText('2026-11-19').closest('tr') as HTMLElement
    expect(within(recent).getAllByText('未到')).toHaveLength(3)

    expect(screen.getByText('820 ms')).toBeInTheDocument()
    expect(screen.getByText('2,100 ms')).toBeInTheDocument()
    expect(screen.getByText('麦克风打开失败')).toBeInTheDocument()
    // 页面不认识的新事件照原名显示，不会被吞掉。
    expect(screen.getByText('something_new')).toBeInTheDocument()
  })

  it('says so when there is nothing to show yet', async () => {
    vi.mocked(analyticsApi.getAnalyticsOverview).mockResolvedValue({ cohorts: [], latency: [], events: [] })

    render(<AnalyticsPage />)

    expect(await screen.findByText('最近 30 天没有新注册的用户')).toBeInTheDocument()
    expect(screen.getByText('还没有上报的事件')).toBeInTheDocument()
  })
})
