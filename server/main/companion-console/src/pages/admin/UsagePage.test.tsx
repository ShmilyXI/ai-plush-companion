import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as usageApi from '../../api/usage'
import { UsagePage } from './UsagePage'

vi.mock('../../api/usage', async () => {
  const actual = await vi.importActual<typeof import('../../api/usage')>('../../api/usage')
  return { ...actual, listDailyUsage: vi.fn(), listUserUsage: vi.fn() }
})

const today: usageApi.DailyUsage = {
  usageDate: '2026-11-20', users: 3, turns: 42, audioTurns: 5,
  promptTokens: 12345, completionTokens: 678, ttsChars: 900, avgFirstTextMs: 640,
}
const yesterday: usageApi.DailyUsage = {
  usageDate: '2026-11-19', users: 1, turns: 2, audioTurns: 0,
  promptTokens: 0, completionTokens: 0, ttsChars: 0, avgFirstTextMs: null,
}

describe('UsagePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(usageApi.listDailyUsage).mockResolvedValue([today, yesterday])
    vi.mocked(usageApi.listUserUsage).mockImplementation(async (date) => date === '2026-11-20'
      ? [{ userId: '9', turns: 30, audioTurns: 5, promptTokens: 9000, completionTokens: 500 }]
      : [{ userId: '7', turns: 2, audioTurns: 0, promptTokens: 0, completionTokens: 0 }])
  })

  it('shows daily totals and marks token counts the provider did not report as unknown', async () => {
    render(<UsagePage />)

    const row = (await screen.findByRole('button', { name: '查看 2026-11-20 的用户用量' })).closest('tr') as HTMLElement
    expect(within(row).getByText('42')).toBeInTheDocument()
    expect(within(row).getByText('12,345 / 678')).toBeInTheDocument()
    expect(within(row).getByText('640 ms')).toBeInTheDocument()
    const quiet = screen.getByRole('button', { name: '查看 2026-11-19 的用户用量' }).closest('tr') as HTMLElement
    expect(within(quiet).getByText('未知')).toBeInTheDocument()
    expect(within(quiet).getByText('-')).toBeInTheDocument()
  })

  it('loads the heaviest users of the newest day first and of another day on request', async () => {
    const user = userEvent.setup()
    render(<UsagePage />)

    await waitFor(() => expect(usageApi.listUserUsage).toHaveBeenCalledWith('2026-11-20'))
    expect(await screen.findByText('2026-11-20 用得最多的用户')).toBeInTheDocument()
    expect(await screen.findByText('9,000 / 500')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '查看 2026-11-19 的用户用量' }))

    await waitFor(() => expect(usageApi.listUserUsage).toHaveBeenCalledWith('2026-11-19'))
    expect(await screen.findByText('2026-11-19 用得最多的用户')).toBeInTheDocument()
  })
})
