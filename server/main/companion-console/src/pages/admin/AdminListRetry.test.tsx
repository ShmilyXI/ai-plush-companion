import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as adminApi from '../../api/admin'
import { AuditLogPage } from './AuditLogPage'
import { DeviceFleetPage } from './DeviceFleetPage'
import { FirmwareManagementPage } from './FirmwareManagementPage'
import { PlanManagementPage } from './PlanManagementPage'
import { TemplateManagementPage } from './TemplateManagementPage'
import { UserManagementPage } from './UserManagementPage'

vi.mock('../../api/admin', async () => {
  const actual = await vi.importActual<typeof import('../../api/admin')>('../../api/admin')
  return {
    ...actual,
    listUsers: vi.fn(),
    listDevices: vi.fn(),
    listFirmware: vi.fn(),
    listAudit: vi.fn(),
    listTemplates: vi.fn(),
    listPlans: vi.fn(),
  }
})

describe('administrator list retry', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [], total: 0 })
  })

  it.each([
    ['用户管理', UserManagementPage, adminApi.listUsers, { id: 'u1', username: 'alice', mobile: '', status: 1 }],
    ['设备运营', DeviceFleetPage, adminApi.listDevices, { id: 'd1', alias: '书房设备' }],
    ['固件管理', FirmwareManagementPage, adminApi.listFirmware, { id: 'f1', firmwareName: '稳定固件', version: '1.0.0', type: 'esp32' }],
    ['审计日志', AuditLogPage, adminApi.listAudit, { id: 'a1', operatorId: 1, targetUserId: null, action: 'firmware.upload', resourceType: 'firmware', resourceId: 'f1', summary: '', createdAt: '2026-07-29' }],
    ['角色模板', TemplateManagementPage, adminApi.listTemplates, { id: 't1', agentCode: 'friend', agentName: '陪伴模板', raw: {} }],
    ['套餐与订阅', PlanManagementPage, adminApi.listPlans, { id: 'p1', planCode: 'pro', planName: '专业版', maxDevices: 5, maxProfiles: 10, longTermMemory: 1, advancedVoice: 1, status: 1 }],
  ] as const)('%s retries the failed page with the current search and page', async (_title, Page, api, row) => {
    const user = userEvent.setup()
    let failRequestedPage = true
    vi.mocked(api).mockImplementation(async (keyword, current) => {
      if (keyword === '保留条件' && current === 2 && failRequestedPage) {
        failRequestedPage = false
        throw new Error('列表暂时不可用')
      }
      return { list: [row] as never[], total: 21 }
    })

    const view = render(<Page />)
    const search = await screen.findByLabelText('关键词')
    await user.type(search, '保留条件{enter}')
    await waitFor(() => expect(api).toHaveBeenCalledWith('保留条件', 1, 20, expect.anything()))
    await user.click(screen.getByTitle('2'))

    expect(await screen.findByText('列表暂时不可用')).toBeInTheDocument()
    vi.mocked(api).mockClear()
    await user.click(screen.getByRole('button', { name: /重\s*试/ }))

    await waitFor(() => expect(api).toHaveBeenCalledWith('保留条件', 2, 20, expect.anything()))
    view.unmount()
  })

  it('retries the failed plan user source without resetting the plan query or page', async () => {
    const user = userEvent.setup()
    vi.mocked(adminApi.listUsers)
      .mockRejectedValueOnce(new Error('用户列表暂时不可用'))
      .mockRejectedValueOnce(new Error('用户列表暂时不可用'))
      .mockResolvedValueOnce({ list: [{ id: 'u1', username: 'alice', mobile: '', status: 1 }], total: 1 })
    vi.mocked(adminApi.listPlans).mockResolvedValue({
      list: [{ id: 'p1', planCode: 'pro', planName: '专业版', maxDevices: 5, maxProfiles: 10, longTermMemory: 1, advancedVoice: 1, status: 1 }],
      total: 21,
    })
    render(<PlanManagementPage />)

    expect(await screen.findByText('用户列表暂时不可用')).toBeInTheDocument()
    const search = await screen.findByLabelText('关键词')
    await user.type(search, '保留套餐{enter}')
    await waitFor(() => expect(adminApi.listPlans).toHaveBeenCalledWith('保留套餐', 1, 20, expect.anything()))
    await user.click(screen.getByTitle('2'))
    await waitFor(() => expect(adminApi.listPlans).toHaveBeenCalledWith('保留套餐', 2, 20, expect.anything()))

    expect(screen.getByText('用户列表暂时不可用')).toBeInTheDocument()
    vi.mocked(adminApi.listUsers).mockClear()
    vi.mocked(adminApi.listPlans).mockClear()
    await user.click(screen.getByRole('button', { name: /重\s*试/ }))

    await waitFor(() => expect(adminApi.listUsers).toHaveBeenCalledWith('', 1, 20, expect.anything()))
    expect(adminApi.listPlans).not.toHaveBeenCalled()
    expect(await screen.findByText('用户列表暂时不可用')).toBeInTheDocument()
    expect(search).toHaveValue('保留套餐')
    expect(screen.getByTitle('2')).toHaveClass('ant-pagination-item-active')

    vi.mocked(adminApi.listUsers).mockClear()
    await user.click(screen.getByRole('button', { name: /重\s*试/ }))
    await waitFor(() => expect(adminApi.listUsers).toHaveBeenCalledWith('', 1, 20, expect.anything()))
    await waitFor(() => expect(screen.queryByText('用户列表暂时不可用')).not.toBeInTheDocument())
  })
})
