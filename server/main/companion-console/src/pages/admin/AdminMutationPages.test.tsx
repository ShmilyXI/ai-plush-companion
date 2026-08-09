import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { message, Modal } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as adminApi from '../../api/admin'
import { FirmwareManagementPage } from './FirmwareManagementPage'
import { PlanManagementPage } from './PlanManagementPage'

vi.mock('../../api/admin', async () => {
  const actual = await vi.importActual<typeof import('../../api/admin')>('../../api/admin')
  return {
    ...actual,
    listFirmware: vi.fn(),
    uploadFirmware: vi.fn(),
    updateFirmware: vi.fn(),
    deleteFirmware: vi.fn(),
    listPlans: vi.fn(),
    listUsers: vi.fn(),
    grantSubscription: vi.fn(),
    pauseSubscription: vi.fn(),
    cancelSubscription: vi.fn(),
    deletePlan: vi.fn(),
    updatePlan: vi.fn(),
  }
})

describe('administrator mutation pages', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.resetAllMocks()
  })

  it('searches and deletes firmware, and keeps a failed upload open without success feedback', async () => {
    vi.mocked(adminApi.listFirmware).mockResolvedValue({ list: [{ id: 'f1', firmwareName: '稳定固件', version: '1.0.0', type: 'esp32' }], total: 21 })
    vi.mocked(adminApi.deleteFirmware).mockResolvedValue(undefined)
    vi.mocked(adminApi.uploadFirmware).mockRejectedValue(new Error('上传被拒绝'))
    const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never)
    let confirmDelete: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirmDelete = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<FirmwareManagementPage />)
    await screen.findByText('稳定固件')

    await userEvent.type(screen.getByRole('searchbox'), 'esp{enter}')
    await waitFor(() => expect(adminApi.listFirmware).toHaveBeenCalledWith('esp', 1, 20, expect.anything()))
    await userEvent.click(screen.getByTitle('2'))
    await waitFor(() => expect(adminApi.listFirmware).toHaveBeenCalledWith('esp', 2, 20, expect.anything()))

    await userEvent.click(screen.getByRole('button', { name: /删\s*除/ }))
    await confirmDelete?.()
    expect(adminApi.deleteFirmware).toHaveBeenCalledWith('f1')

    await userEvent.click(screen.getByRole('button', { name: '新建固件' }))
    const file = new File([new Uint8Array([1, 2, 3])], 'firmware.bin', { type: 'application/octet-stream' })
    await userEvent.upload(screen.getByLabelText('固件文件'), file)
    await userEvent.type(screen.getByLabelText('固件名称'), '测试固件')
    await userEvent.type(screen.getByLabelText('版本'), '2.0.0')
    await userEvent.type(screen.getByLabelText('类型'), 'esp32')
    await userEvent.click(screen.getByRole('button', { name: /上\s*传/ }))
    expect(await screen.findByText('上传被拒绝')).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '上传固件' })).toBeInTheDocument()
    expect(success).not.toHaveBeenCalledWith('固件已上传')
  })

  it('searches and deletes plans, and keeps a failed grant open', async () => {
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [{ id: 'pro', planCode: 'pro', planName: '专业版', maxDevices: 5, maxProfiles: 10, longTermMemory: 1, advancedVoice: 1, status: 1 }], total: 21 })
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [{ id: 'u1', username: 'alice', mobile: '', status: 1 }], total: 1 })
    vi.mocked(adminApi.deletePlan).mockResolvedValue(undefined)
    vi.mocked(adminApi.grantSubscription).mockRejectedValue(new Error('授权失败'))
    let confirmDelete: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirmDelete = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<PlanManagementPage />)
    await screen.findByText('专业版')

    await userEvent.type(screen.getByRole('searchbox'), '专业{enter}')
    await waitFor(() => expect(adminApi.listPlans).toHaveBeenCalledWith('专业', 1, 20, expect.anything()))
    await userEvent.click(screen.getByTitle('2'))
    await waitFor(() => expect(adminApi.listPlans).toHaveBeenCalledWith('专业', 2, 20, expect.anything()))
    await userEvent.click(screen.getByRole('button', { name: /删\s*除/ }))
    await confirmDelete?.()
    expect(adminApi.deletePlan).toHaveBeenCalledWith('pro')

    await userEvent.click(screen.getByRole('button', { name: '授权套餐' }))
    await userEvent.click(screen.getByRole('button', { name: '确认授权' }))
    expect(await screen.findByText('授权失败')).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '授权套餐' })).toBeInTheDocument()
  })

  it('keeps the newest remote user search result', async () => {
    let resolveOld!: (value: Awaited<ReturnType<typeof adminApi.listUsers>>) => void
    let resolveNew!: (value: Awaited<ReturnType<typeof adminApi.listUsers>>) => void
    const oldResult = new Promise<Awaited<ReturnType<typeof adminApi.listUsers>>>((resolve) => { resolveOld = resolve })
    const newResult = new Promise<Awaited<ReturnType<typeof adminApi.listUsers>>>((resolve) => { resolveNew = resolve })
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [{ id: 'pro', planCode: 'pro', planName: '专业版', maxDevices: 5, maxProfiles: 10, longTermMemory: 1, advancedVoice: 1, status: 1 }], total: 1 })
    vi.mocked(adminApi.listUsers).mockImplementation((query) => {
      if (query === 'old') return oldResult
      if (query === 'new') return newResult
      return Promise.resolve({ list: [{ id: 'u1', username: 'initial', mobile: '', status: 1 }], total: 1 })
    })
    render(<PlanManagementPage />)
    await userEvent.click(await screen.findByRole('button', { name: '授权套餐' }))
    const userSearch = screen.getByRole('combobox', { name: '用户' })
    fireEvent.change(userSearch, { target: { value: 'old' } })
    fireEvent.change(userSearch, { target: { value: 'new' } })
    resolveNew({ list: [{ id: 'new', username: 'new-user', mobile: '', status: 1 }], total: 1 })
    await userEvent.click(userSearch)
    expect(await screen.findByText('new-user')).toBeInTheDocument()

    resolveOld({ list: [{ id: 'old', username: 'old-user', mobile: '', status: 1 }], total: 1 })
    await waitFor(() => expect(screen.queryByText('old-user')).not.toBeInTheDocument())
  })

  it('pauses the selected user subscription only after confirmation', async () => {
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [{ id: 'pro', planCode: 'pro', planName: '专业版', maxDevices: 5, maxProfiles: 10, longTermMemory: 1, advancedVoice: 1, status: 1 }], total: 1 })
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [{ id: 'u1', username: 'alice', mobile: '', status: 1 }], total: 1 })
    vi.mocked(adminApi.pauseSubscription).mockResolvedValue(undefined)
    const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never)
    let confirmPause: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirmPause = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<PlanManagementPage />)

    await userEvent.click(await screen.findByRole('button', { name: '管理用户订阅' }))
    await userEvent.click(screen.getByRole('button', { name: '暂停订阅' }))
    expect(adminApi.pauseSubscription).not.toHaveBeenCalled()
    await confirmPause?.()

    expect(adminApi.pauseSubscription).toHaveBeenCalledWith('u1')
    expect(success).toHaveBeenCalledWith('订阅已暂停')
  })

  it('keeps subscription management open and shows no success after cancellation fails', async () => {
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [{ id: 'pro', planCode: 'pro', planName: '专业版', maxDevices: 5, maxProfiles: 10, longTermMemory: 1, advancedVoice: 1, status: 1 }], total: 1 })
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [{ id: 'u1', username: 'alice', mobile: '', status: 1 }], total: 1 })
    vi.mocked(adminApi.cancelSubscription).mockRejectedValue(new Error('订阅已经取消'))
    const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never)
    let confirmCancel: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirmCancel = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<PlanManagementPage />)

    await userEvent.click(await screen.findByRole('button', { name: '管理用户订阅' }))
    await userEvent.click(screen.getByRole('button', { name: '取消订阅' }))
    await expect(confirmCancel?.()).rejects.toThrow('订阅已经取消')

    expect(await screen.findByText('订阅已经取消')).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '管理用户订阅' })).toBeInTheDocument()
    expect(success).not.toHaveBeenCalledWith('订阅已取消')
  })
})
