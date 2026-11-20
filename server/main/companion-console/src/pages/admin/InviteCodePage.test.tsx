import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as inviteApi from '../../api/inviteCodes'
import { InviteCodePage } from './InviteCodePage'

vi.mock('../../api/inviteCodes', async () => {
  const actual = await vi.importActual<typeof import('../../api/inviteCodes')>('../../api/inviteCodes')
  return {
    ...actual,
    listInviteCodes: vi.fn(),
    createInviteCodes: vi.fn(),
    setInviteCodeEnabled: vi.fn(),
    getInviteRequired: vi.fn(),
    setInviteRequired: vi.fn(),
  }
})

function code(overrides: Partial<inviteApi.InviteCode> = {}): inviteApi.InviteCode {
  return {
    id: 'i1',
    code: 'ABCD2345',
    maxUses: 5,
    usedCount: 2,
    expireDate: null,
    enabled: true,
    usable: true,
    remark: '第一批内测',
    createDate: '2026-11-20T10:00:00',
    ...overrides,
  }
}

describe('InviteCodePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(inviteApi.getInviteRequired).mockResolvedValue(true)
    vi.mocked(inviteApi.listInviteCodes).mockResolvedValue({
      total: 2,
      list: [code(), code({ id: 'i2', code: 'WXYZ6789', usedCount: 5, usable: false, remark: null })],
    })
  })

  it('lists codes with how much of each has been used', async () => {
    render(<InviteCodePage />)

    const row = (await screen.findByText('ABCD2345')).closest('tr') as HTMLElement
    expect(within(row).getByText('2 / 5')).toBeInTheDocument()
    expect(within(row).getByText('可用')).toBeInTheDocument()
    const usedUp = screen.getByText('WXYZ6789').closest('tr') as HTMLElement
    expect(within(usedUp).getByText('已用完')).toBeInTheDocument()
    expect(await screen.findByText(/邀请制已开启/)).toBeInTheDocument()
  })

  it('generates codes and shows them for copying', async () => {
    const user = userEvent.setup()
    vi.mocked(inviteApi.createInviteCodes).mockResolvedValue([code({ id: 'n1', code: 'NEWCODE2', usedCount: 0 })])
    render(<InviteCodePage />)

    await user.click(await screen.findByRole('button', { name: '生成邀请码' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('备注'), '给小王')
    await user.click(within(dialog).getByRole('button', { name: /生.*成/ }))

    await waitFor(() => expect(inviteApi.createInviteCodes).toHaveBeenCalledWith({
      count: 1, maxUses: 1, expireDate: null, remark: '给小王',
    }))
    expect(await within(dialog).findByLabelText('生成的邀请码')).toHaveTextContent('NEWCODE2')
  })

  it('turns the invite requirement off and disables a single code', async () => {
    const user = userEvent.setup()
    vi.mocked(inviteApi.setInviteRequired).mockResolvedValue(undefined)
    vi.mocked(inviteApi.setInviteCodeEnabled).mockResolvedValue(undefined)
    render(<InviteCodePage />)

    const gate = await screen.findByRole('switch', { name: '开启邀请制' })
    await waitFor(() => expect(gate).toBeEnabled())
    await user.click(gate)
    await waitFor(() => expect(inviteApi.setInviteRequired).toHaveBeenCalledWith(false))
    expect(await screen.findByText(/邀请制已关闭/)).toBeInTheDocument()

    await user.click(await screen.findByRole('switch', { name: '启用邀请码 ABCD2345' }))
    await waitFor(() => expect(inviteApi.setInviteCodeEnabled).toHaveBeenCalledWith('i1', false))
  })
})
