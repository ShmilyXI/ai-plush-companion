import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http, { ApiError } from '../../api/http'
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
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
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
    vi.spyOn(deviceApi, 'listProfiles').mockResolvedValue([
      { id: 'profile-1', name: '小智' },
      { id: 'profile-2', name: '阿伴' },
    ])
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

  it('shows an offline command as failed and keeps the selected target value', async () => {
    vi.spyOn(deviceApi, 'sendDeviceCommand').mockRejectedValue(
      new ApiError(10205, '设备离线，无法确认执行'),
    )
    renderPage()
    const user = userEvent.setup()

    const volume = await screen.findByRole('slider', { name: '音量' })
    expect(volume).toHaveAttribute('aria-valuenow', '50')
    expect(screen.getByText('这里设置的是待下发目标值，不代表设备当前状态。')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '应用音量' }))

    expect(await screen.findByText('设备离线，音量没有更改')).toBeVisible()
    expect(volume).toHaveAttribute('aria-valuenow', '50')
    expect(screen.queryByText('音量已更新')).not.toBeInTheDocument()
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
