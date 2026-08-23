import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { message, Modal } from 'antd'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '../../auth/authStore'
import { RequireAdmin, RequireRoutePermission } from '../../app/router'
import * as adminApi from '../../api/admin'
import { AuditLogPage } from './AuditLogPage'
import { DeviceFleetPage } from './DeviceFleetPage'
import { FirmwareManagementPage } from './FirmwareManagementPage'
import { PlanManagementPage } from './PlanManagementPage'
import { SystemSettingsPage } from './SystemSettingsPage'
import { TemplateManagementPage } from './TemplateManagementPage'
import { UserManagementPage } from './UserManagementPage'
import { AdminPage } from './AdminPage'

vi.mock('../../api/admin', async () => {
  const actual = await vi.importActual<typeof import('../../api/admin')>('../../api/admin')
  return {
    ...actual,
    listPlans: vi.fn(),
    listUsers: vi.fn(),
    grantSubscription: vi.fn(),
    listAudit: vi.fn(),
    listDevices: vi.fn(),
    listAdminMemories: vi.fn(),
    updateAdminMemory: vi.fn(),
    deleteAdminMemory: vi.fn(),
    clearAdminMemories: vi.fn(),
    renameAdminDevice: vi.fn(),
    unbindAdminDevice: vi.fn(),
    listFirmware: vi.fn(),
    listTemplates: vi.fn(),
    getSystemSettings: vi.fn(),
    saveSystemSettings: vi.fn(),
    changeUserStatus: vi.fn(),
    createTemplate: vi.fn(),
    updateTemplate: vi.fn(),
    deleteTemplate: vi.fn(),
  }
})

describe('administrator routes', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('uses the shared admin page shell for errors and actions', () => {
    const retry = vi.fn()
    render(<AdminPage title="测试管理页" error="加载失败" onRetry={retry} actions={<button type="button">新建</button>}><div>内容</div></AdminPage>)

    expect(screen.getByRole('heading', { name: '测试管理页' })).toBeVisible()
    expect(screen.getByText('加载失败').closest('.admin-page-alert')).not.toBeNull()
    expect(screen.getByRole('button', { name: '新建' })).toBeVisible()
  })

  it('returns an ordinary user to the dashboard before rendering admin content', async () => {
    useAuthStore.setState({
      status: 'authenticated', token: 'normal', permissions: ['sys:role:normal'],
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })
    render(<MemoryRouter initialEntries={['/admin/plans']}><Routes>
      <Route path="/admin" element={<RequireAdmin />}><Route path="plans" element={<p>套餐管理私密内容</p>} /></Route>
      <Route path="/dashboard" element={<p>首页</p>} />
    </Routes></MemoryRouter>)
    expect(await screen.findByText('首页')).toBeInTheDocument()
    expect(screen.queryByText('套餐管理私密内容')).not.toBeInTheDocument()
  })

  it('protects the system settings route from ordinary users', async () => {
    useAuthStore.setState({
      status: 'authenticated', token: 'normal', permissions: ['sys:role:normal'],
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })
    render(<MemoryRouter initialEntries={['/admin/system-settings']}><Routes>
      <Route path="/admin" element={<RequireAdmin />}><Route path="system-settings" element={<p>系统设置私密内容</p>} /></Route>
      <Route path="/dashboard" element={<p>首页</p>} />
    </Routes></MemoryRouter>)
    expect(await screen.findByText('首页')).toBeInTheDocument()
    expect(screen.queryByText('系统设置私密内容')).not.toBeInTheDocument()
  })

  it('protects the capability center from ordinary users', async () => {
    useAuthStore.setState({
      status: 'authenticated', token: 'normal', permissions: ['sys:role:normal'],
      user: { id: '7', username: 'demo', superAdmin: 0, status: 1 },
    })
    render(<MemoryRouter initialEntries={['/admin/capabilities']}><Routes>
      <Route path="/admin/capabilities" element={<RequireRoutePermission routeKey="capabilities" />}>
        <Route index element={<p>能力中心私密内容</p>} />
      </Route>
      <Route path="/dashboard" element={<p>首页</p>} />
    </Routes></MemoryRouter>)
    expect(await screen.findByText('首页')).toBeInTheDocument()
    expect(screen.queryByText('能力中心私密内容')).not.toBeInTheDocument()
  })

  it('grants a plan only after confirmation and reports the server result', async () => {
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [{ id: 'pro', planCode: 'pro', planName: '专业版', maxDevices: 5, maxProfiles: 10, longTermMemory: 1, advancedVoice: 1, status: 1 }], total: 1 })
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [{ id: '9', username: 'alice', mobile: '13800000000', status: 1 }], total: 1 })
    vi.mocked(adminApi.grantSubscription).mockResolvedValue(undefined)
    render(<PlanManagementPage />)
    await screen.findByText('专业版')
    await userEvent.click(screen.getByRole('button', { name: '授权套餐' }))
    await userEvent.click(screen.getByRole('button', { name: '确认授权' }))
    await waitFor(() => expect(adminApi.grantSubscription).toHaveBeenCalledOnce())
    expect(await screen.findByText('授权成功')).toBeInTheDocument()
  })

  it('loads safe server data on every administrator page', async () => {
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [{ id: '9', username: 'alice', mobile: '138****0000', status: 1 }], total: 1 })
    vi.mocked(adminApi.listDevices).mockResolvedValue({ list: [{ id: 'd1', alias: '书房设备' }], total: 1 })
    vi.mocked(adminApi.listTemplates).mockResolvedValue({ list: [{ id: 't1', agentName: '陪伴模板', raw: {} }], total: 1 })
    vi.mocked(adminApi.listFirmware).mockResolvedValue({ list: [{ id: 'f1', firmwareName: '稳定固件', version: '1.0.0', type: 'esp32' }], total: 1 })
    vi.mocked(adminApi.getSystemSettings).mockResolvedValue({
      publicWebsocketUrl: 'wss://pet.example/ws', publicOtaUrl: 'https://pet.example/ota/',
      xiaozhiListenHost: '0.0.0.0', xiaozhiListenPort: 8000, otaListenHost: '0.0.0.0', otaListenPort: 8002,
      defaultLlmModelId: 'llm-1', defaultTtsModelId: 'tts-1', defaultAsrModelId: 'asr-1', defaultVadModelId: 'vad-1', defaultTtsVoiceId: 'voice-1',
      modelOptions: { LLM: [], VLLM: [], TTS: [], ASR: [], VAD: [], Memory: [] }, voices: [],
      health: { xiaozhi: { status: 'unknown', address: '0.0.0.0:8000', checkedAt: '2026-08-04T00:00:00Z' }, ota: { status: 'unknown', address: '0.0.0.0:8002', checkedAt: '2026-08-04T00:00:00Z' } },
      restartRequired: false, restartServices: [],
    })
    vi.mocked(adminApi.listAudit).mockResolvedValue({ list: [{ id: 'a1', operatorId: 1, targetUserId: 9, action: 'subscription.grant', resourceType: 'subscription', resourceId: '9', summary: 'planId=pro', createdAt: '2026-07-29' }], total: 1 })

    for (const [Page, content] of [
      [UserManagementPage, 'alice'],
      [DeviceFleetPage, '书房设备'],
      [TemplateManagementPage, '陪伴模板'],
      [FirmwareManagementPage, '稳定固件'],
      [SystemSettingsPage, '设备连接'],
      [AuditLogPage, 'subscription.grant'],
    ] as const) {
      const view = render(<Page />)
      expect(await screen.findByText(content)).toBeInTheDocument()
      view.unmount()
    }
  })

  it('searches and paginates the user, device, and audit read views', async () => {
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [{ id: 'u1', username: 'alice', mobile: '', status: 1 }], total: 21 })
    vi.mocked(adminApi.listDevices).mockResolvedValue({ list: [{ id: 'd1', alias: '书房设备' }], total: 21 })
    vi.mocked(adminApi.listAudit).mockResolvedValue({ list: [{ id: 'a1', operatorId: 1, targetUserId: null, action: 'firmware.upload', resourceType: 'firmware', resourceId: 'f1', summary: '', createdAt: '2026-07-29' }], total: 21 })

    for (const [Page, api] of [
      [UserManagementPage, adminApi.listUsers],
      [DeviceFleetPage, adminApi.listDevices],
      [AuditLogPage, adminApi.listAudit],
    ] as const) {
      const view = render(<Page />)
      const search = await screen.findByLabelText('关键词')
      await userEvent.type(search, '关键字{enter}')
      await waitFor(() => expect(api).toHaveBeenCalledWith('关键字', 1, 20, expect.anything()))
      await userEvent.click(screen.getByTitle('2'))
      await waitFor(() => expect(api).toHaveBeenCalledWith('关键字', 2, 20, expect.anything()))
      view.unmount()
    }

    const audit = render(<AuditLogPage />)
    await screen.findByText('firmware.upload')
    expect(screen.queryByRole('button', { name: /删除|编辑|停用|启用/ })).not.toBeInTheDocument()
    audit.unmount()
  })

  it('uses the platform administration titles', async () => {
    vi.mocked(adminApi.listDevices).mockResolvedValue({ list: [], total: 0 })
    vi.mocked(adminApi.listPlans).mockResolvedValue({ list: [], total: 0 })
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [], total: 0 })

    const devices = render(<DeviceFleetPage />)
    expect(await screen.findByRole('heading', { name: '设备运营' })).toBeInTheDocument()
    devices.unmount()

    const plans = render(<PlanManagementPage />)
    expect(await screen.findByRole('heading', { name: '套餐与订阅' })).toBeInTheDocument()
    plans.unmount()
  })

  it('keeps the newest user table response', async () => {
    let resolveOld!: (value: Awaited<ReturnType<typeof adminApi.listUsers>>) => void
    let resolveNew!: (value: Awaited<ReturnType<typeof adminApi.listUsers>>) => void
    vi.mocked(adminApi.listUsers).mockImplementation((keyword) => {
      if (keyword === 'old') return new Promise((resolve) => { resolveOld = resolve })
      if (keyword === 'new') return new Promise((resolve) => { resolveNew = resolve })
      return Promise.resolve({ list: [], total: 0 })
    })
    render(<UserManagementPage />)
    const search = await screen.findByLabelText('关键词')
    await userEvent.type(search, 'old{enter}')
    await waitFor(() => expect(adminApi.listUsers).toHaveBeenCalledWith('old', 1, 20, expect.anything()))
    await userEvent.clear(search)
    await userEvent.type(search, 'new{enter}')
    await waitFor(() => expect(adminApi.listUsers).toHaveBeenCalledWith('new', 1, 20, expect.anything()))

    resolveNew({ list: [{ id: 'new', username: 'new-user', mobile: '', status: 1 }], total: 1 })
    expect(await screen.findByText('new-user')).toBeInTheDocument()
    resolveOld({ list: [{ id: 'old', username: 'old-user', mobile: '', status: 1 }], total: 1 })
    await waitFor(() => expect(screen.queryByText('old-user')).not.toBeInTheDocument())
  })

  it('manages the selected users memories from the device fleet', async () => {
    vi.mocked(adminApi.listDevices).mockResolvedValue({ list: [{ id: 'd1', alias: '书房设备', bindUserName: 'alice' }], total: 1 })
    vi.mocked(adminApi.listAdminMemories).mockResolvedValue([
      { id: 'm1', content: '喜欢咖啡', updatedAt: '2026-07-31T20:00:00Z', sourceDeviceId: 'd1', sourceProfileId: 'p1', sourceDeviceName: '书房设备', sourceProfileName: '小智' },
      { id: 'm2', content: '周末散步', updatedAt: '2026-07-31T20:10:00Z', sourceDeviceId: 'd1', sourceProfileId: 'p1', sourceDeviceName: '书房设备', sourceProfileName: '小智' },
    ])
    vi.mocked(adminApi.updateAdminMemory).mockResolvedValue(undefined)
    vi.mocked(adminApi.deleteAdminMemory).mockResolvedValue(undefined)
    vi.mocked(adminApi.clearAdminMemories).mockResolvedValue(undefined)
    render(<DeviceFleetPage />)

    await userEvent.click(await screen.findByRole('button', { name: '管理记忆' }))
    expect(await screen.findByText('喜欢咖啡')).toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: '纠正记忆' })[0])
    const content = screen.getByLabelText('记忆内容')
    await userEvent.clear(content)
    await userEvent.type(content, '喜欢热咖啡')
    await userEvent.click(screen.getByRole('button', { name: '保存纠正' }))
    await waitFor(() => expect(adminApi.updateAdminMemory).toHaveBeenCalledWith('d1', 'm1', '喜欢热咖啡', expect.anything()))
    expect(await screen.findByText('喜欢热咖啡')).toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: '删除记忆' })[0])
    await userEvent.click(screen.getByRole('button', { name: '确认删除' }))
    await waitFor(() => expect(adminApi.deleteAdminMemory).toHaveBeenCalledWith('d1', 'm1', expect.anything()))
    expect(screen.queryByText('喜欢热咖啡')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '清空绑定角色记忆' }))
    await userEvent.click(screen.getByRole('button', { name: '确认清空' }))
    await waitFor(() => expect(adminApi.clearAdminMemories).toHaveBeenCalledWith('d1', expect.anything()))
    expect(screen.queryByText('周末散步')).not.toBeInTheDocument()
  }, 10_000)

  it('keeps administrator memory content when the provider rejects a mutation', async () => {
    vi.mocked(adminApi.listDevices).mockResolvedValue({ list: [{ id: 'd1', alias: '书房设备', bindUserName: 'alice' }], total: 1 })
    vi.mocked(adminApi.listAdminMemories).mockResolvedValue([
      { id: 'm1', content: '原始记忆', updatedAt: '2026-07-31T20:00:00Z', sourceDeviceId: 'd1', sourceProfileId: 'p1', sourceDeviceName: '书房设备', sourceProfileName: '小智' },
    ])
    vi.mocked(adminApi.deleteAdminMemory).mockRejectedValue(new Error('记忆服务暂不可用'))
    render(<DeviceFleetPage />)

    await userEvent.click(await screen.findByRole('button', { name: '管理记忆' }))
    await screen.findByText('原始记忆')
    await userEvent.click(screen.getByRole('button', { name: '删除记忆' }))
    await userEvent.click(screen.getByRole('button', { name: '确认删除' }))

    expect(await screen.findByText('记忆服务暂不可用')).toBeInTheDocument()
    expect(screen.getByText('原始记忆')).toBeInTheDocument()
  })

  it('renames and unbinds an administrator device only after success and confirmation', async () => {
    vi.mocked(adminApi.listDevices)
      .mockResolvedValueOnce({ list: [{ id: 'd1', alias: '书房设备', macAddress: 'AA:BB', bindUserName: 'alice' }], total: 1 })
      .mockResolvedValueOnce({ list: [{ id: 'd1', alias: '床头伙伴', macAddress: 'AA:BB', bindUserName: 'alice' }], total: 1 })
      .mockResolvedValueOnce({ list: [], total: 0 })
    vi.mocked(adminApi.renameAdminDevice).mockResolvedValue(undefined)
    vi.mocked(adminApi.unbindAdminDevice).mockResolvedValue(undefined)
    let confirmUnbind: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirmUnbind = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<DeviceFleetPage />)

    await userEvent.click(await screen.findByRole('button', { name: '编辑名称' }))
    const alias = screen.getByLabelText('设备名称')
    await userEvent.clear(alias)
    await userEvent.type(alias, '床头伙伴')
    await userEvent.click(screen.getByRole('button', { name: '保存名称' }))
    await waitFor(() => expect(adminApi.renameAdminDevice).toHaveBeenCalledWith('d1', '床头伙伴'))
    expect(await screen.findByText('床头伙伴')).toBeInTheDocument()
    expect(screen.getByText('AA:BB')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '解绑设备' }))
    expect(adminApi.unbindAdminDevice).not.toHaveBeenCalled()
    await confirmUnbind?.()
    expect(adminApi.unbindAdminDevice).toHaveBeenCalledWith('d1')
    await waitFor(() => expect(screen.queryByText('床头伙伴')).not.toBeInTheDocument())
  })

  it('keeps an administrator device row when unbind fails', async () => {
    vi.mocked(adminApi.listDevices).mockResolvedValue({ list: [{ id: 'd1', alias: '书房设备', bindUserName: 'alice' }], total: 1 })
    vi.mocked(adminApi.unbindAdminDevice).mockRejectedValue(new Error('解绑失败'))
    const errorMessage = vi.spyOn(message, 'error').mockImplementation(() => undefined as never)
    let confirmUnbind: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirmUnbind = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<DeviceFleetPage />)

    await userEvent.click(await screen.findByRole('button', { name: '解绑设备' }))
    await expect(confirmUnbind?.()).rejects.toThrow('解绑失败')

    expect(errorMessage).toHaveBeenCalledWith('解绑失败')
    expect(screen.getByText('书房设备')).toBeInTheDocument()
  })

  it('changes user status only through confirmation', async () => {
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [{ id: 'u1', username: 'alice', mobile: '', status: 1 }], total: 1 })
    vi.mocked(adminApi.changeUserStatus).mockResolvedValue(undefined)
    let confirm: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirm = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<UserManagementPage />)
    await userEvent.click(await screen.findByRole('button', { name: /停\s*用/ }))
    expect(adminApi.changeUserStatus).not.toHaveBeenCalled()
    await confirm?.()
    expect(adminApi.changeUserStatus).toHaveBeenCalledWith('u1', 0)
  })

  it('shows a safe user-status failure and keeps the row', async () => {
    vi.mocked(adminApi.listUsers).mockResolvedValue({ list: [{ id: 'u1', username: 'alice', mobile: '', status: 1 }], total: 1 })
    vi.mocked(adminApi.changeUserStatus).mockRejectedValue(new Error('状态更新失败'))
    const errorMessage = vi.spyOn(message, 'error').mockImplementation(() => undefined as never)
    let confirm: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirm = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<UserManagementPage />)
    await userEvent.click(await screen.findByRole('button', { name: /停\s*用/ }))

    await expect(confirm?.()).rejects.toThrow('状态更新失败')

    expect(errorMessage).toHaveBeenCalledWith('状态更新失败')
    expect(screen.getByText('alice')).toBeInTheDocument()
  })

  it('creates, edits, and deletes templates through server mutations', async () => {
    vi.mocked(adminApi.listTemplates).mockResolvedValue({ list: [{ id: 't1', agentCode: 'friend', agentName: '老朋友', systemPrompt: '原设定', raw: {} }], total: 1 })
    vi.mocked(adminApi.createTemplate).mockResolvedValue(undefined)
    vi.mocked(adminApi.updateTemplate).mockResolvedValue(undefined)
    vi.mocked(adminApi.deleteTemplate).mockResolvedValue(undefined)
    let confirmDelete: (() => Promise<void>) | undefined
    vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      confirmDelete = options.onOk as () => Promise<void>
      return { destroy: vi.fn(), update: vi.fn() } as never
    })
    render(<TemplateManagementPage />)
    await screen.findByText('老朋友')

    await userEvent.click(screen.getByRole('button', { name: '新建模板' }))
    await userEvent.type(screen.getByLabelText('模板代码'), 'new-friend')
    await userEvent.type(screen.getByLabelText('模板名称'), '新朋友')
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }))
    await waitFor(() => expect(adminApi.createTemplate).toHaveBeenCalledWith(expect.objectContaining({ agentCode: 'new-friend', agentName: '新朋友' })))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '新建模板' })).not.toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: /编\s*辑/ }))
    const name = screen.getByLabelText('模板名称')
    await userEvent.clear(name)
    await userEvent.type(name, '老友')
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }))
    await waitFor(() => expect(adminApi.updateTemplate).toHaveBeenCalledWith('t1', expect.objectContaining({ agentCode: 'friend', agentName: '老友' })))

    await userEvent.click(screen.getByRole('button', { name: /删\s*除/ }))
    await confirmDelete?.()
    expect(adminApi.deleteTemplate).toHaveBeenCalledWith('t1')
  })

  it('keeps the template editor open and shows a failed save', async () => {
    vi.mocked(adminApi.listTemplates).mockResolvedValue({ list: [], total: 0 })
    vi.mocked(adminApi.createTemplate).mockRejectedValue(new Error('模板保存失败'))
    render(<TemplateManagementPage />)
    await screen.findByRole('button', { name: '新建模板' })
    await userEvent.click(screen.getByRole('button', { name: '新建模板' }))
    await userEvent.type(screen.getByLabelText('模板代码'), 'friend')
    await userEvent.type(screen.getByLabelText('模板名称'), '朋友')
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }))

    const templateDialog = screen.getByRole('dialog', { name: '新建模板' })
    expect(await within(templateDialog).findByText('模板保存失败')).toBeInTheDocument()
    expect(within(templateDialog).getByLabelText('模板名称')).toHaveValue('朋友')
    await userEvent.click(within(templateDialog).getByRole('button', { name: /取\s*消/ }))
    await userEvent.click(screen.getByRole('button', { name: '新建模板' }))
    expect(within(screen.getByRole('dialog', { name: '新建模板' })).queryByText('模板保存失败')).not.toBeInTheDocument()
  })
})
