import '@ant-design/v5-patch-for-react-19'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
    createPlan: vi.fn(),
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

    await userEvent.type(screen.getByLabelText('关键词'), 'esp{enter}')
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
    const firmwareDialog = screen.getByRole('dialog', { name: '上传固件' })
    expect(await within(firmwareDialog).findByText('上传被拒绝')).toBeInTheDocument()
    expect(within(firmwareDialog).getByLabelText('固件名称')).toHaveValue('测试固件')
    expect(success).not.toHaveBeenCalledWith('固件已上传')
    await userEvent.click(within(firmwareDialog).getByRole('button', { name: /取\s*消/ }))
    await userEvent.click(screen.getByRole('button', { name: '新建固件' }))
    expect(within(screen.getByRole('dialog', { name: '上传固件' })).queryByText('上传被拒绝')).not.toBeInTheDocument()
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

    await userEvent.type(screen.getByLabelText('关键词'), '专业{enter}')
    await waitFor(() => expect(adminApi.listPlans).toHaveBeenCalledWith('专业', 1, 20, expect.anything()))
    await userEvent.click(screen.getByTitle('2'))
    await waitFor(() => expect(adminApi.listPlans).toHaveBeenCalledWith('专业', 2, 20, expect.anything()))
    await userEvent.click(screen.getByRole('button', { name: /删\s*除/ }))
    await confirmDelete?.()
    expect(adminApi.deletePlan).toHaveBeenCalledWith('pro')

    await userEvent.click(screen.getByRole('button', { name: '授权套餐' }))
    await userEvent.click(screen.getByRole('button', { name: '确认授权' }))
    const grantDialog = screen.getByRole('dialog', { name: '授权套餐' })
    expect(await within(grantDialog).findByText('授权失败')).toBeInTheDocument()
    await userEvent.click(within(grantDialog).getByRole('button', { name: /取\s*消/ }))
    await userEvent.click(screen.getByRole('button', { name: '授权套餐' }))
    expect(within(screen.getByRole('dialog', { name: '授权套餐' })).queryByText('授权失败')).not.toBeInTheDocument()
  })

  it('prevents switching plan mutations while a request is pending', async () => {
    let resolveGrant!: () => void
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [{ id: 'pro', planCode: 'pro', planName: '专业版', maxDevices: 5, maxProfiles: 10, longTermMemory: 1, advancedVoice: 1, status: 1 }], total: 1 })
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [{ id: 'u1', username: 'alice', mobile: '', status: 1 }], total: 1 })
    vi.mocked(adminApi.grantSubscription).mockImplementation(() => new Promise<void>((resolve) => { resolveGrant = resolve }))
    render(<PlanManagementPage />)

    await userEvent.click(await screen.findByRole('button', { name: '授权套餐' }))
    const grantDialog = screen.getByRole('dialog', { name: '授权套餐' })
    const grantButton = within(grantDialog).getByRole('button', { name: '确认授权' })
    fireEvent.click(grantButton)
    fireEvent.click(grantButton)
    await waitFor(() => expect(adminApi.grantSubscription).toHaveBeenCalledOnce())

    expect(within(grantDialog).getByRole('button', { name: /取\s*消/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: '管理用户订阅' })).toBeDisabled()
    fireEvent.click(within(grantDialog).getByRole('button', { name: /取\s*消/ }))
    fireEvent.click(screen.getByRole('button', { name: '管理用户订阅' }))
    expect(screen.getByRole('dialog', { name: '授权套餐' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: '管理用户订阅' })).not.toBeInTheDocument()

    resolveGrant()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '授权套餐' })).not.toBeInTheDocument())
  })

  it('keeps a failed plan save error inside the editor', async () => {
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [], total: 0 })
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [], total: 0 })
    vi.mocked(adminApi.createPlan).mockRejectedValue(new Error('套餐保存失败'))
    render(<PlanManagementPage />)

    await userEvent.click(await screen.findByRole('button', { name: '新建套餐' }))
    const planDialog = screen.getByRole('dialog', { name: '新建套餐' })
    await userEvent.type(within(planDialog).getByLabelText('套餐代码'), 'starter')
    await userEvent.type(within(planDialog).getByLabelText('套餐名称'), '入门版')
    await userEvent.click(within(planDialog).getByRole('button', { name: /保\s*存/ }))

    expect(await within(planDialog).findByText('套餐保存失败')).toBeInTheDocument()
    expect(within(planDialog).getByLabelText('套餐名称')).toHaveValue('入门版')
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

  it('closes the confirmation and reveals a retryable subscription failure in the management dialog', async () => {
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [{ id: 'pro', planCode: 'pro', planName: '专业版', maxDevices: 5, maxProfiles: 10, longTermMemory: 1, advancedVoice: 1, status: 1 }], total: 1 })
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [{ id: 'u1', username: 'alice', mobile: '', status: 1 }], total: 1 })
    vi.mocked(adminApi.cancelSubscription).mockRejectedValueOnce(new Error('订阅已经取消')).mockResolvedValueOnce(undefined)
    const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never)
    render(<PlanManagementPage />)

    await userEvent.click(await screen.findByRole('button', { name: '管理用户订阅' }))
    const subscriptionDialog = screen.getByRole('dialog', { name: '管理用户订阅' })
    await userEvent.click(within(subscriptionDialog).getByRole('button', { name: '取消订阅' }))
    await waitFor(() => expect(document.querySelector('.ant-modal-confirm')).not.toBeNull())
    const firstConfirmation = document.querySelector('.ant-modal-confirm') as HTMLElement
    await userEvent.click(within(firstConfirmation).getByRole('button', { name: /确认取消/ }))

    await waitFor(() => expect(document.querySelector('.ant-modal-confirm')).toBeNull())
    expect(await within(subscriptionDialog).findByText('订阅已经取消')).toBeInTheDocument()
    expect(success).not.toHaveBeenCalledWith('订阅已取消')

    await userEvent.click(within(subscriptionDialog).getByRole('button', { name: '取消订阅' }))
    await waitFor(() => expect(document.querySelector('.ant-modal-confirm')).not.toBeNull())
    const retryConfirmation = document.querySelector('.ant-modal-confirm') as HTMLElement
    await userEvent.click(within(retryConfirmation).getByRole('button', { name: /确认取消/ }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '管理用户订阅' })).not.toBeInTheDocument())
    expect(adminApi.cancelSubscription).toHaveBeenCalledTimes(2)
    expect(success).toHaveBeenCalledWith('订阅已取消')
  })
})
