import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { message, Modal } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as adminApi from '../../api/admin'
import { ResourceManagementPage } from './ResourceManagementPage'

vi.mock('../../api/admin', async () => {
  const actual = await vi.importActual<typeof import('../../api/admin')>('../../api/admin')
  return {
    ...actual,
    listModels: vi.fn(),
    listProviders: vi.fn(),
    listTimbres: vi.fn(),
    listTtsModelOptions: vi.fn(),
    listVoiceResources: vi.fn(),
    deleteVoiceResource: vi.fn(),
    updateModel: vi.fn(),
    createModel: vi.fn(),
    deleteModel: vi.fn(),
    setModelEnabled: vi.fn(),
    setDefaultModel: vi.fn(),
    createTimbre: vi.fn(),
    updateTimbre: vi.fn(),
    deleteTimbre: vi.fn(),
  }
})

describe('ResourceManagementPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(adminApi.listModels).mockResolvedValue({ list: [], total: 0 })
    vi.mocked(adminApi.listProviders).mockResolvedValue([{ code: 'openai', name: 'OpenAI' }])
    vi.mocked(adminApi.listTimbres).mockResolvedValue({ list: [], total: 0 })
    vi.mocked(adminApi.listTtsModelOptions).mockResolvedValue([{ id: 'tts1', name: 'TTS', type: 'TTS', modelCode: 'SafeTTS', providerCode: 'openai', enabled: true, isDefault: true }])
    vi.mocked(adminApi.listVoiceResources).mockResolvedValue({ list: [{ id: 'clone1', name: '克隆音色甲', type: 'voiceClone', enabled: true, isDefault: false }], total: 21 })
  })

  it('searches and paginates cloned voices and keeps the row when deletion fails', async () => {
    vi.mocked(adminApi.deleteVoiceResource).mockRejectedValue(new Error('删除失败'))
    const errorMessage = vi.spyOn(message, 'error').mockImplementation(() => undefined as never)
    let confirmDelete: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirmDelete = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<ResourceManagementPage />)

    await userEvent.click(await screen.findByRole('tab', { name: '音色资源' }))
    await userEvent.click(await screen.findByRole('tab', { name: '克隆音色' }))
    expect(await screen.findByText('克隆音色甲')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /删\s*除/ }))
    await expect(confirmDelete?.()).rejects.toThrow('删除失败')
    expect(errorMessage).toHaveBeenCalledWith('删除失败')
    await waitFor(() => expect(adminApi.deleteVoiceResource).toHaveBeenCalledWith('clone1'))
    expect(screen.getByText('克隆音色甲')).toBeInTheDocument()

    const search = screen.getByRole('searchbox')
    await userEvent.type(search, '甲{enter}')
    await waitFor(() => expect(adminApi.listVoiceResources).toHaveBeenCalledWith('甲', 1, 20, expect.anything()))

    await userEvent.click(screen.getByTitle('2'))
    await waitFor(() => expect(adminApi.listVoiceResources).toHaveBeenCalledWith('甲', 2, 20, expect.anything()))
  }, 15000)

  it('keeps the model editor open and shows a failed save', async () => {
    vi.mocked(adminApi.listModels).mockResolvedValue({ list: [{ id: 'm1', name: '原模型', type: 'Memory', modelCode: 'fixed', providerCode: 'openai', enabled: true, isDefault: false }], total: 1 })
    vi.mocked(adminApi.updateModel).mockRejectedValue(new Error('模型保存失败'))
    render(<ResourceManagementPage />)
    await userEvent.click(await screen.findByRole('button', { name: /编\s*辑/ }))
    const dialog = await screen.findByRole('dialog', { name: '编辑模型' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }))

    expect(await screen.findByText('模型保存失败')).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '编辑模型' })).toBeInTheDocument()
  })

  it('shows profile and device usage for administrator models', async () => {
    vi.mocked(adminApi.listModels).mockResolvedValue({ list: [{ id: 'm1', name: '对话模型', type: 'Memory', modelCode: 'memory', providerCode: 'openai', enabled: true, isDefault: false, profileUsageCount: 3, deviceUsageCount: 2 }], total: 1 })
    render(<ResourceManagementPage />)

    expect(await screen.findByRole('columnheader', { name: '角色使用' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '设备使用' })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /对话模型.*3.*2/ })).toBeInTheDocument()
  })

  it('ignores an older provider response after a newer model tab request', async () => {
    let resolveMemory!: (value: Array<{ code: string; name: string }>) => void
    let resolveAsr!: (value: Array<{ code: string; name: string }>) => void
    const memory = new Promise<Array<{ code: string; name: string }>>((resolve) => { resolveMemory = resolve })
    const asr = new Promise<Array<{ code: string; name: string }>>((resolve) => { resolveAsr = resolve })
    vi.mocked(adminApi.listProviders).mockImplementation((type) => type === 'Memory' ? memory : asr)
    render(<ResourceManagementPage />)
    await screen.findByRole('button', { name: '新增模型' })

    await userEvent.click(screen.getByRole('button', { name: '新增模型' }))
    await userEvent.click(screen.getByRole('tab', { name: 'ASR' }))
    await userEvent.click(screen.getByRole('button', { name: '新增模型' }))
    resolveAsr([{ code: 'asr-provider', name: 'ASR Provider' }])

    const dialog = await screen.findByRole('dialog', { name: '新增模型' })
    expect(within(dialog).getByLabelText('类别（仅创建时设置）')).toHaveValue('ASR')
    resolveMemory([{ code: 'memory-provider', name: 'Memory Provider' }])
    await waitFor(() => expect(within(dialog).getByLabelText('类别（仅创建时设置）')).toHaveValue('ASR'))
  })

  it('aborts provider loading when the active model tab changes', async () => {
    let resolveMemory!: (value: Array<{ code: string; name: string }>) => void
    let memorySignal: AbortSignal | undefined
    const memory = new Promise<Array<{ code: string; name: string }>>((resolve) => { resolveMemory = resolve })
    vi.mocked(adminApi.listProviders).mockImplementation((_type, options) => {
      memorySignal = options?.signal
      return memory
    })
    render(<ResourceManagementPage />)
    await screen.findByRole('button', { name: '新增模型' })

    await userEvent.click(screen.getByRole('button', { name: '新增模型' }))
    await userEvent.click(screen.getByRole('tab', { name: 'ASR' }))

    expect(memorySignal?.aborted).toBe(true)
    resolveMemory([{ code: 'memory-provider', name: 'Memory Provider' }])
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '新增模型' })).not.toBeInTheDocument())
  })

  it('covers all model tabs and keeps immutable fields out of model edits', async () => {
    vi.mocked(adminApi.listModels).mockResolvedValue({ list: [{ id: 'm1', name: '原模型', type: 'Memory', modelCode: 'fixed-code', providerCode: 'openai', docLink: 'https://docs.example/model', enabled: true, isDefault: false, remark: '旧备注', sort: 1 }], total: 1 })
    vi.mocked(adminApi.updateModel).mockResolvedValue(undefined)
    vi.mocked(adminApi.createModel).mockResolvedValue(undefined)
    vi.mocked(adminApi.deleteModel).mockResolvedValue(undefined)
    vi.mocked(adminApi.setModelEnabled).mockResolvedValue(undefined)
    vi.mocked(adminApi.setDefaultModel).mockResolvedValue(undefined)
    let confirm: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirm = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<ResourceManagementPage />)
    await screen.findByText('原模型')

    for (const type of ['ASR', 'VAD', 'LLM', 'TTS', 'Memory']) {
      await userEvent.click(screen.getByRole('tab', { name: type }))
      await waitFor(() => expect(adminApi.listModels).toHaveBeenCalledWith(type, '', 1, 20, expect.anything()))
    }

    await userEvent.click(screen.getByRole('button', { name: /编\s*辑/ }))
    const editDialog = await screen.findByRole('dialog', { name: '编辑模型' })
    expect(within(editDialog).getByLabelText('供应器（仅创建时设置）')).toBeDisabled()
    expect(within(editDialog).getByLabelText('模型编码（仅创建时设置）')).toBeDisabled()
    expect(within(editDialog).getByLabelText('文档地址（仅创建时设置）')).toBeDisabled()
    const modelName = within(editDialog).getByLabelText('模型名称')
    await userEvent.clear(modelName)
    await userEvent.type(modelName, '新模型名')
    fireEvent.change(within(editDialog).getByLabelText('配置 JSON（留空不读取或覆盖密钥）'), { target: { value: '{"temperature":0.2}' } })
    await userEvent.click(within(editDialog).getByRole('button', { name: 'OK' }))
    await waitFor(() => expect(adminApi.updateModel).toHaveBeenCalledOnce())
    const update = vi.mocked(adminApi.updateModel).mock.calls[0][1]
    expect(update).toEqual({ modelName: '新模型名', isEnabled: 1, remark: '旧备注', sort: 1, config: { temperature: 0.2 } })
    expect(update).not.toHaveProperty('modelType')
    expect(update).not.toHaveProperty('providerCode')
    expect(update).not.toHaveProperty('modelCode')
    expect(update).not.toHaveProperty('docLink')

    await userEvent.click(screen.getByRole('button', { name: /停\s*用/ }))
    await confirm?.()
    expect(adminApi.setModelEnabled).toHaveBeenCalledWith('m1', false)
    await userEvent.click(screen.getByRole('button', { name: /设为默认/ }))
    await confirm?.()
    expect(adminApi.setDefaultModel).toHaveBeenCalledWith('m1')
    await userEvent.click(screen.getByRole('button', { name: /删\s*除/ }))
    await confirm?.()
    expect(adminApi.deleteModel).toHaveBeenCalledWith('m1')

    await userEvent.click(screen.getByRole('button', { name: '新增模型' }))
    const createDialog = await screen.findByRole('dialog', { name: '新增模型' })
    await userEvent.type(within(createDialog).getByLabelText('模型编码（仅创建时设置）'), 'new-code')
    await userEvent.type(within(createDialog).getByLabelText('模型名称'), '新模型')
    await userEvent.click(within(createDialog).getByRole('button', { name: 'OK' }))
    await waitFor(() => expect(adminApi.createModel).toHaveBeenCalledWith(expect.objectContaining({ modelType: 'Memory', providerCode: 'openai', modelCode: 'new-code', modelName: '新模型' })))
  }, 15000)

  it('creates, edits, and deletes ordinary TTS voices', async () => {
    vi.mocked(adminApi.listTimbres).mockResolvedValue({ list: [{ id: 'v1', name: '普通音色甲', ttsModelId: 'tts1', ttsVoice: 'voice-a', languages: 'zh-CN', sort: 0 }], total: 1 })
    vi.mocked(adminApi.createTimbre).mockResolvedValue(undefined)
    vi.mocked(adminApi.updateTimbre).mockResolvedValue(undefined)
    vi.mocked(adminApi.deleteTimbre).mockResolvedValue(undefined)
    let confirmDelete: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirmDelete = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<ResourceManagementPage />)
    await userEvent.click(await screen.findByRole('tab', { name: '音色资源' }))
    expect(await screen.findByText('普通音色甲')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '新增普通音色' }))
    const createDialog = await screen.findByRole('dialog', { name: '新增音色' })
    await userEvent.type(within(createDialog).getByLabelText('名称'), '普通音色乙')
    await userEvent.type(within(createDialog).getByLabelText('音色编码'), 'voice-b')
    await userEvent.click(within(createDialog).getByRole('button', { name: 'OK' }))
    await waitFor(() => expect(adminApi.createTimbre).toHaveBeenCalledWith(expect.objectContaining({ name: '普通音色乙', ttsModelId: 'tts1', ttsVoice: 'voice-b' })))

    await userEvent.click(screen.getByRole('button', { name: /编\s*辑/ }))
    const editDialog = await screen.findByRole('dialog', { name: '编辑音色' })
    const name = within(editDialog).getByLabelText('名称')
    await userEvent.clear(name)
    await userEvent.type(name, '普通音色甲改')
    await userEvent.click(within(editDialog).getByRole('button', { name: 'OK' }))
    await waitFor(() => expect(adminApi.updateTimbre).toHaveBeenCalledWith('v1', expect.objectContaining({ name: '普通音色甲改' })))

    await userEvent.click(screen.getByRole('button', { name: /删\s*除/ }))
    await confirmDelete?.()
    expect(adminApi.deleteTimbre).toHaveBeenCalledWith('v1')
  }, 15000)
})
