import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as analyticsApi from '../api/analytics'
import * as usageApi from '../api/usage'
import * as companionWeb from '../app/companionWeb'
import { useAuthStore } from '../auth/authStore'
import { OverviewPage } from './OverviewPage'

vi.mock('../api/usage')
vi.mock('../api/analytics')
vi.mock('../app/companionWeb', () => ({ openCompanionWeb: vi.fn(async () => undefined) }))

function dateKey(offsetDays = 0) {
  const date = new Date(Date.now() - offsetDays * 24 * 60 * 60 * 1000)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function usageRow(usageDate: string, users: number, turns: number): usageApi.DailyUsage {
  return { usageDate, users, turns, audioTurns: 1, openings: 2, promptTokens: 0, completionTokens: 0, ttsChars: 0, avgFirstTextMs: null }
}

function signIn(superAdmin: 0 | 1) {
  useAuthStore.getState().setSessionForTest({
    token: 'token',
    user: { id: superAdmin ? '1' : '7', username: superAdmin ? 'admin' : 'demo', superAdmin, status: 1 },
  })
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <Routes>
        <Route path="dashboard" element={<OverviewPage />} />
        <Route path="admin/invite-codes" element={<div>邀请码页面</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

function statistic(title: string) {
  return screen.getByText(title).closest('.ant-statistic')?.querySelector('.ant-statistic-content')?.textContent
}

describe('OverviewPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    useAuthStore.getState().clearSession()
  })

  it('shows today, new users this week and the latest reply speed', async () => {
    signIn(1)
    vi.mocked(usageApi.listDailyUsage).mockResolvedValue([usageRow(dateKey(), 3, 21), usageRow(dateKey(1), 5, 40)])
    vi.mocked(analyticsApi.getAnalyticsOverview).mockResolvedValue({
      cohorts: [
        { date: dateKey(), size: 2, activated: 1, d1: null, d7: null, d30: null },
        { date: dateKey(3), size: 4, activated: 2, d1: 1, d7: null, d30: null },
        { date: dateKey(20), size: 9, activated: 9, d1: 3, d7: 2, d30: null },
      ],
      latency: [
        { date: dateKey(1), turns: 40, p50: 2400, p90: 5000 },
        { date: dateKey(), turns: 21, p50: 1250, p90: 3000 },
      ],
      events: [],
    })

    renderPage()

    await waitFor(() => expect(statistic('今天聊过的用户')).toBe('3'))
    expect(statistic('今天的对话轮数')).toBe('21')
    expect(statistic('近 7 天新用户')).toBe('6')
    expect(statistic('首字耗时中位数')).toContain('1.3 秒')
    expect(usageApi.listDailyUsage).toHaveBeenCalledWith(7)
  })

  it('shows zero for today when nobody has talked yet', async () => {
    signIn(1)
    vi.mocked(usageApi.listDailyUsage).mockResolvedValue([usageRow(dateKey(1), 5, 40)])
    vi.mocked(analyticsApi.getAnalyticsOverview).mockResolvedValue({ cohorts: [], latency: [], events: [] })

    renderPage()

    await waitFor(() => expect(statistic('今天的对话轮数')).toBe('0'))
    expect(statistic('今天聊过的用户')).toBe('0')
    expect(statistic('首字耗时中位数')).toBe('暂无')
  })

  it('keeps the numbers that loaded when the other request fails', async () => {
    signIn(1)
    vi.mocked(usageApi.listDailyUsage).mockResolvedValue([usageRow(dateKey(), 3, 21)])
    vi.mocked(analyticsApi.getAnalyticsOverview).mockRejectedValue(new Error('统计服务暂时不可用'))

    renderPage()

    expect(await screen.findByText('统计服务暂时不可用')).toBeInTheDocument()
    expect(statistic('今天的对话轮数')).toBe('21')
    expect(statistic('近 7 天新用户')).toBe('不可用')
  })

  it('opens the invite code page from the shortcuts', async () => {
    signIn(1)
    vi.mocked(usageApi.listDailyUsage).mockResolvedValue([])
    vi.mocked(analyticsApi.getAnalyticsOverview).mockResolvedValue({ cohorts: [], latency: [], events: [] })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: '生成邀请码' }))

    expect(await screen.findByText('邀请码页面')).toBeInTheDocument()
  })

  it('tells an account without admin rights to use the web app and asks for no admin data', async () => {
    signIn(0)

    renderPage()

    expect(await screen.findByText('这个账号没有管理权限')).toBeInTheDocument()
    // 打开网页端时带上当前账号的登录状态，不用再登录一次。
    await userEvent.setup().click(screen.getByRole('button', { name: '打开网页端' }))
    expect(companionWeb.openCompanionWeb).toHaveBeenCalledTimes(1)
    expect(usageApi.listDailyUsage).not.toHaveBeenCalled()
    expect(analyticsApi.getAnalyticsOverview).not.toHaveBeenCalled()
  })
})
