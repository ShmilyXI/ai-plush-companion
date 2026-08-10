import '@ant-design/v5-patch-for-react-19'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Form, type FormInstance } from 'antd'
import dayjs from 'dayjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as adminApi from '../../api/admin'
import { PlanManagementPage } from './PlanManagementPage'

vi.mock('../../api/admin', async () => {
  const actual = await vi.importActual<typeof import('../../api/admin')>('../../api/admin')
  return { ...actual, listPlans: vi.fn(), listUsers: vi.fn() }
})

const useFormWarning = 'Instance created by `useForm` is not connected to any Form element'
type InternalFormInstance = FormInstance & { getInternalHooks: (secret: string) => unknown }
let errorSpy: ReturnType<typeof vi.spyOn>, warnSpy: ReturnType<typeof vi.spyOn>
let initializationConnections: boolean[], grantInitializationConnections: boolean[]
let grantFormInstance: FormInstance | undefined

async function expectNoUseFormWarning() {
  await new Promise((resolve) => setTimeout(resolve, 0))
  const output = [...errorSpy.mock.calls, ...warnSpy.mock.calls].flat().join(' ')
  expect(output).not.toContain(useFormWarning)
}

describe('PlanManagementPage', () => {
  beforeEach(() => {
    initializationConnections = []
    grantInitializationConnections = []
    grantFormInstance = undefined
    const originalUseForm = Form.useForm
    const trackedForms = new WeakSet<object>()
    vi.spyOn(Form, 'useForm').mockImplementation(((form) => {
      const result = originalUseForm(form)
      const instance = result[0]
      if (!trackedForms.has(instance)) {
        trackedForms.add(instance)
        let connected = false
        const internalInstance = instance as InternalFormInstance
        const originalGetInternalHooks = internalInstance.getInternalHooks.bind(internalInstance)
        vi.spyOn(internalInstance, 'getInternalHooks').mockImplementation((secret) => {
          const hooks = originalGetInternalHooks(secret)
          if (hooks) connected = true
          return hooks
        })
        const originalSetFieldsValue = instance.setFieldsValue.bind(instance)
        vi.spyOn(instance, 'setFieldsValue').mockImplementation((values) => {
          if (values && typeof values === 'object' && 'userId' in values) initializationConnections.push(connected)
          if (values && typeof values === 'object' && 'planId' in values) {
            grantInitializationConnections.push(connected)
            grantFormInstance = instance
          }
          originalSetFieldsValue(values)
        })
      }
      return result
    }) as typeof Form.useForm)
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [], total: 0 })
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [
      { id: 'u1', username: 'alice', mobile: '', status: 1 },
      { id: 'u2', username: 'bob', mobile: '', status: 1 },
    ], total: 2 })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('mounts the subscription form only while open and clears it after closing', async () => {
    render(<PlanManagementPage />)

    expect(screen.queryByLabelText('用户')).not.toBeInTheDocument()
    await userEvent.click(await screen.findByRole('button', { name: '管理用户订阅' }))
    const dialog = await screen.findByRole('dialog', { name: '管理用户订阅' })
    await waitFor(() => expect(initializationConnections).toEqual([true]))
    await expectNoUseFormWarning()
    await userEvent.click(within(dialog).getByRole('combobox', { name: '用户' }))
    await userEvent.click(await screen.findByText('bob'))
    await userEvent.click(within(dialog).getByRole('button', { name: /关\s*闭/ }))

    await waitFor(() => expect(screen.queryByLabelText('用户')).not.toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '管理用户订阅' }))
    expect(await within(await screen.findByRole('dialog', { name: '管理用户订阅' })).findByTitle('alice')).toBeInTheDocument()
    expect(initializationConnections).toEqual([true, true])
    await expectNoUseFormWarning()
  })

  it('keeps the selected subscription user when a search refreshes the options', async () => {
    vi.mocked(adminApi.listUsers).mockImplementation((query) => Promise.resolve(query === 'new'
      ? { list: [{ id: 'u3', username: 'new-user', mobile: '', status: 1 }], total: 1 }
      : { list: [
        { id: 'u1', username: 'alice', mobile: '', status: 1 },
        { id: 'u2', username: 'bob', mobile: '', status: 1 },
      ], total: 2 }))
    render(<PlanManagementPage />)

    await userEvent.click(await screen.findByRole('button', { name: '管理用户订阅' }))
    const dialog = await screen.findByRole('dialog', { name: '管理用户订阅' })
    const userSearch = within(dialog).getByRole('combobox', { name: '用户' })
    await userEvent.click(userSearch)
    await userEvent.click(await screen.findByText('bob'))
    expect(within(dialog).getByTitle('bob')).toBeInTheDocument()

    fireEvent.change(userSearch, { target: { value: 'new' } })
    await waitFor(() => expect(adminApi.listUsers).toHaveBeenLastCalledWith('new', 1, 20, expect.anything()))
    expect(await screen.findByRole('option', { name: 'new-user' })).toBeInTheDocument()
    await waitFor(() => expect(initializationConnections).toEqual([true]))
    expect(within(dialog).getByTitle('bob')).toBeInTheDocument()
    await expectNoUseFormWarning()
  })

  it('connects the grant form before initializing its first values and destroys it after closing', async () => {
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [
      { id: 'basic', planCode: 'basic', planName: 'Basic', maxDevices: 1, maxProfiles: 1, longTermMemory: 0, advancedVoice: 0, status: 1 },
      { id: 'pro', planCode: 'pro', planName: '专业版', maxDevices: 5, maxProfiles: 10, longTermMemory: 1, advancedVoice: 1, status: 1 },
    ], total: 2 })
    render(<PlanManagementPage />)

    expect(screen.queryByRole('dialog', { name: '授权套餐' })).not.toBeInTheDocument()
    await userEvent.click(await screen.findByRole('button', { name: '授权套餐' }))
    const dialog = await screen.findByRole('dialog', { name: '授权套餐' })
    const defaultExpiryDate = dayjs().add(1, 'year').format('YYYY-MM-DD')
    const changedExpiry = dayjs().add(2, 'year').startOf('day')
    await waitFor(() => expect(grantInitializationConnections).toEqual([true]))
    expect(within(dialog).getByTitle('alice')).toBeInTheDocument()
    expect(within(dialog).getByTitle('Basic')).toBeInTheDocument()
    expect((within(dialog).getByLabelText('到期时间') as HTMLInputElement).value).toContain(defaultExpiryDate)
    await expectNoUseFormWarning()

    await userEvent.click(within(dialog).getByRole('combobox', { name: '用户' }))
    await userEvent.click(await screen.findByText('bob'))
    await userEvent.click(within(dialog).getByRole('combobox', { name: '套餐' }))
    await userEvent.click(await screen.findByRole('option', { name: '专业版' }))
    act(() => grantFormInstance?.setFieldValue('expiresAt', changedExpiry))
    await waitFor(() => expect((within(dialog).getByLabelText('到期时间') as HTMLInputElement).value).toContain(changedExpiry.format('YYYY-MM-DD')))

    await userEvent.click(within(dialog).getByRole('button', { name: /取\s*消/ }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '授权套餐' })).not.toBeInTheDocument())
    expect(screen.queryByLabelText('到期时间')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '授权套餐' }))
    const reopenedDialog = await screen.findByRole('dialog', { name: '授权套餐' })
    await waitFor(() => expect(grantInitializationConnections).toEqual([true, true]))
    expect(within(reopenedDialog).getByTitle('alice')).toBeInTheDocument()
    expect(within(reopenedDialog).getByTitle('Basic')).toBeInTheDocument()
    expect((within(reopenedDialog).getByLabelText('到期时间') as HTMLInputElement).value).toContain(defaultExpiryDate)
    await expectNoUseFormWarning()
  })

  it('keeps the selected grant user when a search refreshes the options', async () => {
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [{ id: 'pro', planCode: 'pro', planName: '专业版', maxDevices: 5, maxProfiles: 10, longTermMemory: 1, advancedVoice: 1, status: 1 }], total: 1 })
    vi.mocked(adminApi.listUsers).mockImplementation((query) => Promise.resolve(query === 'new'
      ? { list: [{ id: 'u3', username: 'new-user', mobile: '', status: 1 }], total: 1 }
      : { list: [
        { id: 'u1', username: 'alice', mobile: '', status: 1 },
        { id: 'u2', username: 'bob', mobile: '', status: 1 },
      ], total: 2 }))
    render(<PlanManagementPage />)

    await userEvent.click(await screen.findByRole('button', { name: '授权套餐' }))
    const dialog = await screen.findByRole('dialog', { name: '授权套餐' })
    const userSearch = within(dialog).getByRole('combobox', { name: '用户' })
    await userEvent.click(userSearch)
    await userEvent.click(await screen.findByText('bob'))
    expect(within(dialog).getByTitle('bob')).toBeInTheDocument()

    fireEvent.change(userSearch, { target: { value: 'new' } })
    await waitFor(() => expect(adminApi.listUsers).toHaveBeenLastCalledWith('new', 1, 20, expect.anything()))
    expect(await screen.findByRole('option', { name: 'new-user' })).toBeInTheDocument()
    expect(grantInitializationConnections).toEqual([true])
    expect(within(dialog).getByTitle('bob')).toBeInTheDocument()
  })
})
