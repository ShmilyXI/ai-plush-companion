import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http, { ApiError } from '../../api/http'
import * as capabilityApi from '../../api/capabilities'
import * as debugLogApi from '../../api/deviceDebugLogs'
import * as deviceApi from '../../api/devices'
import { DeviceDetailPage } from './DeviceDetailPage'

const device: deviceApi.CompanionDevice = {
  id: 'device-a',
  macAddress: 'AA:BB:CC:DD:EE:FF',
  alias: '书房伙伴',
  online: true,
  appVersion: '1.2.3',
  hasDisplay: false,
  hasCamera: false,
  activeProfileId: 'profile-1',
  debugLogEnabled: false,
  effectiveModels: [],
}

function renderPage(deviceId = 'device-a') {
  return render(
    <MemoryRouter initialEntries={[`/devices/${encodeURIComponent(deviceId)}`]}>
      <Routes>
        <Route path="/devices/:id" element={<DeviceDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

function RouteControls() {
  const navigate = useNavigate()
  return <button type="button" onClick={() => navigate('/devices/device-b')}>打开设备 B</button>
}

function renderRacePage() {
  return render(
    <MemoryRouter initialEntries={['/devices/device-a']}>
      <Routes>
        <Route path="/devices/:id" element={<><DeviceDetailPage /><RouteControls /></>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('device command API adapter', () => {
  it.each([
    true,
    { success: true },
    { success: true, isError: false },
  ])('accepts an explicit command success result %#', async (data) => {
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data } })

    await expect(deviceApi.sendDeviceCommand('device-a', 'volume', 50)).resolves.toEqual(data)
  })

  it.each([
    false,
    null,
    {},
    { success: false },
    { isError: true },
    { success: true, isError: true },
    { success: true, error: 'device rejected command' },
    'ok',
  ])('rejects an ambiguous or failed command result %#', async (data) => {
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data } })

    await expect(deviceApi.sendDeviceCommand('device-a', 'volume', 50)).rejects.toMatchObject({
      name: 'DeviceCommandError',
      code: 10206,
    })
  })
})

describe('DeviceDetailPage', () => {
  beforeEach(() => {
    vi.spyOn(deviceApi, 'getDevice').mockResolvedValue(device)
    vi.spyOn(debugLogApi, 'getDeviceDebugLogHistory').mockResolvedValue({ events: [], lastCursor: '0-0' })
    vi.spyOn(debugLogApi, 'streamDeviceDebugLogs').mockImplementation((_id, _after, options) => (
      new Promise<void>((_resolve, reject) => {
        options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    ))
    vi.spyOn(deviceApi, 'listProfiles').mockResolvedValue([
      { id: 'profile-1', name: '紫萱' },
      { id: 'profile-2', name: '阿伴' },
    ])
    vi.spyOn(capabilityApi, 'listDeviceSkillCatalog').mockResolvedValue([])
    vi.spyOn(capabilityApi, 'listDeviceSkills').mockResolvedValue([])
    vi.spyOn(capabilityApi, 'saveDeviceSkills').mockResolvedValue([])
  })

  it('keeps device skills with the device while preserving volume and brightness controls', async () => {
    vi.spyOn(deviceApi, 'switchDeviceProfile').mockResolvedValue(undefined)
    vi.mocked(capabilityApi.listDeviceSkillCatalog).mockResolvedValue([{
      skillId: 'skill-weather', name: '天气查询', description: '查询天气', publishedVersion: 1,
      packageVersion: 1, packageSha256: 'a'.repeat(64), packageSource: 'MIGRATION',
      versions: [1], overridableFields: [], defaults: {}, available: true, unavailableReason: null,
    }])
    renderPage()

    expect(await screen.findByText('天气查询')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: '绑定 天气查询' })).not.toBeInTheDocument()
    expect(screen.getByRole('slider', { name: '音量' })).toBeInTheDocument()
    expect(screen.getByRole('slider', { name: '屏幕亮度' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('combobox', { name: '陪伴角色' }))
    await userEvent.click(await screen.findByText('阿伴'))
    await waitFor(() => expect(deviceApi.switchDeviceProfile).toHaveBeenCalledWith(
      'device-a', 'profile-2', expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ))
    expect(capabilityApi.saveDeviceSkills).not.toHaveBeenCalled()
  })

  it('uses the device name as the page heading and shows the Pro summary labels', async () => {
    renderPage()

    const heading = await screen.findByRole('heading', { level: 1, name: '书房伙伴' })
    expect(heading.closest('.ant-pro-page-container')).not.toBeNull()
    expect(screen.getByText('设备状态')).toBeVisible()
    expect(screen.getByText('固件版本')).toBeVisible()
    expect(screen.getByText('当前角色')).toBeVisible()
    expect(screen.getAllByText('设备能力')[0]).toBeVisible()
    expect(screen.getByText('屏幕：不支持')).toBeVisible()
    expect(screen.getByText('摄像头：不支持')).toBeVisible()
  })

  it('renders the debug log panel before the danger card and updates device state immutably', async () => {
    vi.spyOn(deviceApi, 'setDeviceDebugLogging').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    const panel = await screen.findByTestId('device-debug-log-panel')
    const danger = document.querySelector('.danger-card')
    expect(panel.compareDocumentPosition(danger!)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    await user.click(screen.getByRole('switch', { name: '记录调试日志' }))

    expect(deviceApi.setDeviceDebugLogging).toHaveBeenCalledWith('device-a', true)
    expect(screen.getByRole('switch', { name: '记录调试日志' })).toBeChecked()
    expect(screen.getByRole('heading', { level: 1, name: '书房伙伴' })).toBeVisible()
  })

  it('disables controls that the server says the device cannot support', async () => {
    renderPage()

    expect(await screen.findByText('书房伙伴')).toBeVisible()
    expect(screen.getByRole('slider', { name: '屏幕亮度' })).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('button', { name: '摄像头偏好' })).toBeDisabled()
    expect(screen.getByText('此设备没有屏幕')).toBeVisible()
  })

  it('shows the models actually used by the device', async () => {
    vi.spyOn(deviceApi, 'getDevice').mockResolvedValue({ ...device, effectiveModels: [
      { modelType: 'LLM', resourceId: 'private-1', name: '我的 Ollama', source: 'private', modelId: 'qwen', overridden: true, overrides: {} },
    ] })
    renderPage()

    expect(await screen.findByText('我的 Ollama')).toBeInTheDocument()
    expect(screen.getByText('我的私有模型')).toBeInTheDocument()
  })

  it('uses the decoded route id when loading an unusual device id', async () => {
    renderPage('device /?#%')

    await screen.findByText('书房伙伴')
    expect(deviceApi.getDevice).toHaveBeenCalledWith(
      'device /?#%',
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
  })

  it('refreshes the current device every 30 seconds without reloading profiles', async () => {
    vi.useFakeTimers()
    try {
      const view = renderPage()
      expect(deviceApi.getDevice).toHaveBeenCalledTimes(1)
      expect(deviceApi.listProfiles).toHaveBeenCalledTimes(1)

      await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
      expect(deviceApi.getDevice).toHaveBeenCalledTimes(2)
      expect(deviceApi.listProfiles).toHaveBeenCalledTimes(1)

      view.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('ignores an in-flight poll after the debug switch changes and accepts the next poll', async () => {
    vi.useFakeTimers()
    try {
      const stalePoll = deferred<deviceApi.CompanionDevice>()
      vi.mocked(deviceApi.getDevice)
        .mockResolvedValueOnce(device)
        .mockReturnValueOnce(stalePoll.promise)
        .mockResolvedValueOnce({ ...device, debugLogEnabled: false })
      vi.spyOn(deviceApi, 'setDeviceDebugLogging').mockResolvedValue(undefined)
      const view = renderPage()

      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
      fireEvent.click(screen.getByRole('switch', { name: '记录调试日志' }))
      await act(async () => { await Promise.resolve() })
      expect(screen.getByRole('switch', { name: '记录调试日志' })).toBeChecked()
      await act(async () => {
        stalePoll.resolve({ ...device, debugLogEnabled: false })
        await stalePoll.promise
      })
      expect(screen.getByRole('switch', { name: '记录调试日志' })).toBeChecked()

      await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
      expect(screen.getByRole('switch', { name: '记录调试日志' })).not.toBeChecked()
      view.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('settles the initial loading state when the latest poll fails', async () => {
    vi.useFakeTimers()
    try {
      const initialRequest = deferred<deviceApi.CompanionDevice>()
      vi.mocked(deviceApi.getDevice)
        .mockReturnValueOnce(initialRequest.promise)
        .mockRejectedValueOnce(new Error('设备轮询失败'))
      const view = renderPage()

      await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })

      expect(screen.getByText('设备信息无法加载')).toBeVisible()
      expect(screen.getByText('设备轮询失败')).toBeVisible()
      expect(screen.getByRole('button', { name: /重\s*试/ })).toBeVisible()
      await act(async () => {
        initialRequest.resolve(device)
        await initialRequest.promise
      })
      expect(screen.getByText('设备信息无法加载')).toBeVisible()
      expect(screen.queryByRole('heading', { level: 1, name: '书房伙伴' })).not.toBeInTheDocument()
      view.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('ignores an older poll that resolves after a newer device state', async () => {
    vi.useFakeTimers()
    try {
      const olderPoll = deferred<deviceApi.CompanionDevice>()
      const newerPoll = deferred<deviceApi.CompanionDevice>()
      const command = deferred<true>()
      vi.mocked(deviceApi.getDevice)
        .mockResolvedValueOnce(device)
        .mockReturnValueOnce(olderPoll.promise)
        .mockReturnValueOnce(newerPoll.promise)
      vi.spyOn(deviceApi, 'sendDeviceCommand').mockReturnValue(command.promise)
      const view = renderPage()

      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
      await act(async () => {
        newerPoll.resolve({ ...device, online: false })
        await newerPoll.promise
      })
      expect(screen.getByText('离线')).toBeVisible()
      await act(async () => {
        olderPoll.resolve(device)
        await olderPoll.promise
      })

      expect(screen.getByText('离线')).toBeVisible()
      expect(screen.queryByText('在线')).not.toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: '应用音量' }))
      await act(async () => {
        command.reject(new ApiError(10205, '设备离线，无法确认执行'))
        await command.promise.catch(() => undefined)
      })
      expect(screen.getByText('设备离线，音量没有更改')).toBeVisible()
      view.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not let a stale device response overwrite a newer route', async () => {
    const first = deferred<deviceApi.CompanionDevice>()
    vi.spyOn(deviceApi, 'getDevice').mockImplementation((id) => id === 'device-a'
      ? first.promise
      : Promise.resolve({ ...device, id: 'device-b', alias: '设备 B' }))
    renderRacePage()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '打开设备 B' }))
    expect(await screen.findByText('设备 B')).toBeVisible()
    await act(async () => {
      first.resolve({ ...device, alias: '迟到的设备 A' })
      await first.promise
    })

    expect(screen.queryByText('迟到的设备 A')).not.toBeInTheDocument()
    expect(screen.getByText('设备 B')).toBeVisible()
  })

  it('ignores a stale command completion after navigating to another device', async () => {
    const command = deferred<true>()
    vi.spyOn(deviceApi, 'getDevice').mockImplementation((id) => Promise.resolve(
      id === 'device-a' ? device : { ...device, id: 'device-b', alias: '设备 B' },
    ))
    vi.spyOn(deviceApi, 'sendDeviceCommand').mockReturnValue(command.promise)
    renderRacePage()
    const user = userEvent.setup()

    await screen.findByText('书房伙伴')
    await user.click(screen.getByRole('button', { name: '应用音量' }))
    await user.click(screen.getByRole('button', { name: '打开设备 B' }))
    expect(await screen.findByText('设备 B')).toBeVisible()
    await act(async () => {
      command.resolve(true)
      await command.promise
    })

    expect(screen.queryByText('音量已更新')).not.toBeInTheDocument()
    expect(screen.getByText('设备 B')).toBeVisible()
  })

  it('ignores a stale rename completion after navigating to another device', async () => {
    const update = deferred<void>()
    vi.spyOn(deviceApi, 'getDevice').mockImplementation((deviceId) => Promise.resolve(
      deviceId === 'device-a' ? device : { ...device, id: 'device-b', alias: '设备 B' },
    ))
    vi.spyOn(deviceApi, 'updateDevice').mockReturnValue(update.promise)
    renderRacePage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '修改名称' }))
    await user.clear(screen.getByLabelText('设备名称'))
    await user.type(screen.getByLabelText('设备名称'), '迟到的新名称')
    await user.click(screen.getByRole('button', { name: '保存名称' }))
    await user.click(screen.getByRole('button', { name: '打开设备 B' }))
    expect(await screen.findByText('设备 B')).toBeVisible()
    await act(async () => {
      update.resolve()
      await update.promise
    })

    expect(screen.queryByText('迟到的新名称')).not.toBeInTheDocument()
    expect(screen.queryByText('设备名称已更新')).not.toBeInTheDocument()
  })

  it('ignores a stale profile switch completion after navigating to another device', async () => {
    const switching = deferred<void>()
    vi.spyOn(deviceApi, 'getDevice').mockImplementation((deviceId) => Promise.resolve(
      deviceId === 'device-a' ? device : { ...device, id: 'device-b', alias: '设备 B' },
    ))
    vi.spyOn(deviceApi, 'switchDeviceProfile').mockReturnValue(switching.promise)
    renderRacePage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('combobox', { name: '陪伴角色' }))
    await user.click(await screen.findByText('阿伴'))
    await user.click(screen.getByRole('button', { name: '打开设备 B' }))
    expect(await screen.findByText('设备 B')).toBeVisible()
    await act(async () => {
      switching.resolve()
      await switching.promise
    })

    expect(screen.queryByText('陪伴角色已切换')).not.toBeInTheDocument()
    expect(screen.getByText('设备 B')).toBeVisible()
  })

  it('explains when heartbeat is online but the realtime control channel is unavailable', async () => {
    vi.spyOn(deviceApi, 'sendDeviceCommand').mockRejectedValue(
      new ApiError(10205, '设备离线，无法确认执行'),
    )
    renderPage()
    const user = userEvent.setup()

    const volume = await screen.findByRole('slider', { name: '音量' })
    expect(volume).toHaveAttribute('aria-valuenow', '50')
    expect(screen.getByText('这里设置的是待下发目标值，不代表设备当前状态。')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '应用音量' }))

    expect(await screen.findByText('设备心跳在线，但实时控制通道不可用，音量没有更改')).toBeVisible()
    expect(volume).toHaveAttribute('aria-valuenow', '50')
    expect(screen.queryByText('音量已更新')).not.toBeInTheDocument()
  })

  it('uses the latest heartbeat state when a pending command reports the device offline', async () => {
    vi.useFakeTimers()
    try {
      const command = deferred<true>()
      vi.mocked(deviceApi.getDevice)
        .mockResolvedValueOnce(device)
        .mockResolvedValueOnce({ ...device, online: false })
      vi.spyOn(deviceApi, 'sendDeviceCommand').mockReturnValue(command.promise)
      const view = renderPage()

      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      fireEvent.click(screen.getByRole('button', { name: '应用音量' }))
      await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
      expect(screen.getByText('离线')).toBeVisible()
      await act(async () => {
        command.reject(new ApiError(10205, '设备离线，无法确认执行'))
        await command.promise.catch(() => undefined)
      })

      expect(screen.getByText('设备离线，音量没有更改')).toBeVisible()
      expect(screen.queryByText('设备心跳在线，但实时控制通道不可用，音量没有更改')).not.toBeInTheDocument()
      view.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('reports the device as offline when heartbeat is also offline', async () => {
    vi.spyOn(deviceApi, 'getDevice').mockResolvedValue({ ...device, online: false })
    vi.spyOn(deviceApi, 'sendDeviceCommand').mockRejectedValue(
      new ApiError(10205, '设备离线，无法确认执行'),
    )
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '应用音量' }))

    expect(await screen.findByText('设备离线，音量没有更改')).toBeVisible()
  })

  it.each([
    false,
    null,
    {},
    { success: false },
    { isError: true },
    { success: true, error: 'device rejected command' },
  ])('does not report success for failed command payload %#', async (data) => {
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data } })
    renderPage()
    const user = userEvent.setup()

    const volume = await screen.findByRole('slider', { name: '音量' })
    await user.click(screen.getByRole('button', { name: '应用音量' }))

    expect(await screen.findByText('设备未能应用音量')).toBeVisible()
    expect(volume).toHaveAttribute('aria-valuenow', '50')
    expect(screen.queryByText('音量已更新')).not.toBeInTheDocument()
  })

  it('keeps device controls available when the profile list fails', async () => {
    vi.spyOn(deviceApi, 'listProfiles').mockRejectedValue(new Error('角色列表加载失败'))
    renderPage()

    expect(await screen.findByText('书房伙伴')).toBeVisible()
    expect(screen.getByText('角色列表加载失败')).toBeVisible()
    expect(screen.getByRole('button', { name: '应用音量' })).toBeEnabled()
    expect(screen.getByRole('combobox', { name: '陪伴角色' })).toBeDisabled()
  })

  it('renames the owned device without exposing internal identifiers as editable fields', async () => {
    const update = vi.spyOn(deviceApi, 'updateDevice').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '修改名称' }))
    const alias = screen.getByLabelText('设备名称')
    await user.clear(alias)
    await user.type(alias, '床头伙伴')
    await user.click(screen.getByRole('button', { name: '保存名称' }))

    await waitFor(() => expect(update).toHaveBeenCalledWith(
      'device-a',
      { alias: '床头伙伴' },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ))
    expect(screen.queryByLabelText('设备编号')).not.toBeInTheDocument()
  })

  it('keeps empty rename validation handled without submitting', async () => {
    const update = vi.spyOn(deviceApi, 'updateDevice').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '修改名称' }))
    await user.clear(screen.getByLabelText('设备名称'))
    await user.click(screen.getByRole('button', { name: '保存名称' }))

    expect(await screen.findByText('请输入设备名称')).toBeVisible()
    expect(update).not.toHaveBeenCalled()
  })
})
