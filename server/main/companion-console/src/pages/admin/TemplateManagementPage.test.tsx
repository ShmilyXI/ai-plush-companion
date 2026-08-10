import '@ant-design/v5-patch-for-react-19'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Form, type FormInstance } from 'antd'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as adminApi from '../../api/admin'
import { TemplateManagementPage } from './TemplateManagementPage'

vi.mock('../../api/admin', async () => {
  const actual = await vi.importActual<typeof import('../../api/admin')>('../../api/admin')
  return { ...actual, listTemplates: vi.fn() }
})

const useFormWarning = 'Instance created by `useForm` is not connected to any Form element'
type InternalFormInstance = FormInstance & { getInternalHooks: (secret: string) => unknown }
let errorSpy: ReturnType<typeof vi.spyOn>, warnSpy: ReturnType<typeof vi.spyOn>
let initializationConnections: boolean[]

async function expectNoUseFormWarning() {
  await new Promise((resolve) => setTimeout(resolve, 0))
  const output = [...errorSpy.mock.calls, ...warnSpy.mock.calls].flat().join(' ')
  expect(output).not.toContain(useFormWarning)
}

describe('TemplateManagementPage', () => {
  beforeEach(() => {
    initializationConnections = []
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
          if (values && typeof values === 'object' && 'agentName' in values) initializationConnections.push(connected)
          originalSetFieldsValue(values)
        })
      }
      return result
    }) as typeof Form.useForm)
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    vi.mocked(adminApi.listTemplates).mockResolvedValue({ list: [
      { id: 't1', agentCode: 'friend', agentName: '老朋友', systemPrompt: '原设定', raw: {} },
      { id: 't2', agentCode: 'mentor', agentName: '导师', systemPrompt: '第二设定', raw: {} },
    ], total: 2 })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('connects the form before initializing the first new template', async () => {
    render(<TemplateManagementPage />)

    expect(screen.queryByLabelText('模板名称')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '新建模板' }))
    const dialog = await screen.findByRole('dialog', { name: '新建模板' })
    await waitFor(() => expect(initializationConnections).toEqual([true]))
    await expectNoUseFormWarning()
    await userEvent.type(within(dialog).getByLabelText('模板名称'), '临时模板')
    await userEvent.click(within(dialog).getByRole('button', { name: /取\s*消/ }))

    await waitFor(() => expect(screen.queryByLabelText('模板名称')).not.toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '新建模板' }))
    expect(await screen.findByLabelText('模板名称')).toHaveValue('')
    expect(initializationConnections).not.toContain(false)
    await expectNoUseFormWarning()
  })

  it('connects the form before initializing the first edited template', async () => {
    render(<TemplateManagementPage />)

    await userEvent.click((await screen.findAllByRole('button', { name: /编\s*辑/ }))[0])
    const dialog = await screen.findByRole('dialog', { name: '编辑模板' })

    await waitFor(() => expect(initializationConnections).toEqual([true]))
    expect(within(dialog).getByLabelText('模板代码')).toHaveValue('friend')
    expect(within(dialog).getByLabelText('模板名称')).toHaveValue('老朋友')
    await expectNoUseFormWarning()
  })

  it('initializes the newly selected template after the previous form is destroyed', async () => {
    render(<TemplateManagementPage />)

    const editButtons = await screen.findAllByRole('button', { name: /编\s*辑/ })
    await userEvent.click(editButtons[0])
    let dialog = await screen.findByRole('dialog', { name: '编辑模板' })
    await userEvent.click(within(dialog).getByRole('button', { name: /取\s*消/ }))
    await waitFor(() => expect(screen.queryByLabelText('模板名称')).not.toBeInTheDocument())
    await userEvent.click(editButtons[1])
    dialog = await screen.findByRole('dialog', { name: '编辑模板' })

    await waitFor(() => expect(initializationConnections).toEqual([true, true]))
    expect(within(dialog).getByLabelText('模板代码')).toHaveValue('mentor')
    expect(within(dialog).getByLabelText('模板名称')).toHaveValue('导师')
    expect(within(dialog).getByLabelText('角色设定')).toHaveValue('第二设定')
    await expectNoUseFormWarning()
  })
})
