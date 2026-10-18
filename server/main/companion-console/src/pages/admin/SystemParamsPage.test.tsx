import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { listSystemParams, updateSystemParam, type SystemParam } from '../../api/admin'
import { SystemParamsPage } from './SystemParamsPage'

vi.mock('../../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/admin')>()
  return { ...actual, listSystemParams: vi.fn(), updateSystemParam: vi.fn() }
})

const params: SystemParam[] = [
  { id: '101', paramCode: 'aliyun.sms.access_key_id', paramValue: 'LTAI5tExample', valueType: 'string', paramType: 1, remark: '阿里云短信 AccessKeyId' },
  { id: '102', paramCode: 'server.name', paramValue: '紫萱平台', valueType: 'string', paramType: 1, remark: '服务名称' },
]

describe('SystemParamsPage', () => {
  beforeEach(() => {
    vi.mocked(listSystemParams).mockReset()
    vi.mocked(updateSystemParam).mockReset()
    vi.mocked(listSystemParams).mockResolvedValue({ list: params, total: params.length })
  })

  it('列出参数并对参数值默认脱敏，可切换显示', async () => {
    render(<SystemParamsPage />)
    expect(await screen.findByText('aliyun.sms.access_key_id')).toBeInTheDocument()
    expect(screen.queryByText('LTAI5tExample')).not.toBeInTheDocument()
    expect(screen.getAllByText('LTAI••••le').length).toBeGreaterThan(0)

    const secretRow = screen.getByText('aliyun.sms.access_key_id').closest('tr')
    expect(secretRow).not.toBeNull()
    await userEvent.click(within(secretRow as HTMLElement).getByLabelText('显示参数值'))
    expect(screen.getByText('LTAI5tExample')).toBeInTheDocument()
  })

  it('搜索词透传给接口', async () => {
    render(<SystemParamsPage />)
    expect(await screen.findByText('aliyun.sms.access_key_id')).toBeInTheDocument()
    await userEvent.type(screen.getByRole('textbox', { name: /参数编码/ }), 'aliyun')
    await userEvent.click(screen.getByRole('button', { name: /查 询/ }))
    await waitFor(() => expect(listSystemParams).toHaveBeenCalledWith('aliyun', expect.anything(), expect.anything(), expect.anything()))
  })

  it('编辑参数并保存后提交完整参数并刷新列表', async () => {
    vi.mocked(updateSystemParam).mockResolvedValue()
    render(<SystemParamsPage />)
    expect(await screen.findByText('aliyun.sms.access_key_id')).toBeInTheDocument()

    const row = screen.getByText('aliyun.sms.access_key_id').closest('tr') as HTMLElement
    await userEvent.click(within(row).getByText('编辑'))
    const dialog = await screen.findByRole('dialog')
    const valueBox = dialog.querySelector('textarea') as HTMLTextAreaElement
    await userEvent.clear(valueBox)
    await userEvent.type(valueBox, 'LTAI5tNewKey')
    await userEvent.click(within(dialog).getByRole('button', { name: /保 存/ }))

    await waitFor(() => expect(updateSystemParam).toHaveBeenCalledTimes(1))
    const submitted = vi.mocked(updateSystemParam).mock.calls[0][0]
    expect(submitted.id).toBe('101')
    expect(submitted.paramCode).toBe('aliyun.sms.access_key_id')
    expect(submitted.paramValue).toBe('LTAI5tNewKey')
    expect(submitted.valueType).toBe('string')
    await waitFor(() => expect(listSystemParams).toHaveBeenCalledTimes(2))
  })

  it('保存失败时展示错误且不关闭弹窗', async () => {
    vi.mocked(updateSystemParam).mockRejectedValue(new Error('网络错误'))
    render(<SystemParamsPage />)
    expect(await screen.findByText('server.name')).toBeInTheDocument()

    const row = screen.getByText('server.name').closest('tr') as HTMLElement
    await userEvent.click(within(row).getByText('编辑'))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: /保 存/ }))

    await waitFor(() => expect(screen.getByText('网络错误')).toBeInTheDocument())
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
