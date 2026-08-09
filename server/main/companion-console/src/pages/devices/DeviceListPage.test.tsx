import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from '../../api/http'
import { bindDevice } from '../../api/devices'
import * as deviceApi from '../../api/devices'
import { DeviceListPage } from './DeviceListPage'

const devices: deviceApi.CompanionDevice[] = [
  {
    id: 'device-online',
    macAddress: 'AA:BB:CC:DD:EE:01',
    alias: '客厅伙伴',
    online: true,
    appVersion: '1.2.3',
    hasDisplay: true,
    hasCamera: false,
    activeProfileId: 'profile-1',
  },
  {
    id: 'device-offline',
    macAddress: 'AA:BB:CC:DD:EE:02',
    alias: '卧室伙伴',
    online: false,
    appVersion: '1.1.0',
    hasDisplay: false,
    hasCamera: true,
    activeProfileId: 'profile-2',
  },
]

function renderPage() {
  return render(<MemoryRouter><DeviceListPage /></MemoryRouter>)
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

describe('device API adapter', () => {
  it('submits the six-digit activation code to the companion bind endpoint', async () => {
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data: null } })

    await bindDevice({ activationCode: '123456' })

    expect(http.post).toHaveBeenCalledWith('/companion/devices/bind', { activationCode: '123456' }, undefined)
  })
})

describe('DeviceListPage', () => {
  beforeEach(() => {
    vi.spyOn(deviceApi, 'listDevices').mockResolvedValue(devices)
  })

  it('renders server-provided capabilities and textual online status', async () => {
    renderPage()

    expect(await screen.findByText('客厅伙伴')).toBeVisible()
    expect(screen.getByText('在线')).toBeVisible()
    expect(screen.getByText('离线')).toBeVisible()
    expect(screen.getByText('屏幕')).toBeVisible()
    expect(screen.getByText('摄像头')).toBeVisible()
  })

  it('encodes unusual device ids in detail links', async () => {
    vi.mocked(deviceApi.listDevices).mockResolvedValue([{ ...devices[0], id: 'device /?#%' }])
    renderPage()

    expect(await screen.findByRole('link', { name: '管理客厅伙伴' })).toHaveAttribute(
      'href',
      `/devices/${encodeURIComponent('device /?#%')}`,
    )
  })

  it('validates a six-digit binding code before submitting', async () => {
    const bind = vi.spyOn(deviceApi, 'bindDevice').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '绑定设备' }))
    await user.type(screen.getByLabelText('六位绑定码'), '12345')
    await user.click(screen.getByRole('button', { name: '确认绑定' }))

    expect(await screen.findByText('请输入六位数字绑定码')).toBeVisible()
    expect(bind).not.toHaveBeenCalled()

    await user.type(screen.getByLabelText('六位绑定码'), '6')
    await user.click(screen.getByRole('button', { name: '确认绑定' }))

    await waitFor(() => expect(bind).toHaveBeenCalledWith(
      { activationCode: '123456' },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ))
  })

  it('does not let the initial list response overwrite a newer post-bind refresh', async () => {
    const first = deferred<deviceApi.CompanionDevice[]>()
    vi.mocked(deviceApi.listDevices)
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(devices)
    vi.spyOn(deviceApi, 'bindDevice').mockResolvedValue(undefined)
    renderPage()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '绑定设备' }))
    await user.type(screen.getByLabelText('六位绑定码'), '123456')
    await user.click(screen.getByRole('button', { name: '确认绑定' }))
    expect(await screen.findByText('客厅伙伴')).toBeVisible()
    await act(async () => {
      first.resolve([])
      await first.promise
    })

    expect(screen.getByText('客厅伙伴')).toBeVisible()
    expect(screen.queryByText('还没有绑定设备')).not.toBeInTheDocument()
  })

  it('aborts the list request when the page unmounts', () => {
    let signal: AbortSignal | undefined
    vi.mocked(deviceApi.listDevices).mockImplementation((options) => {
      signal = options?.signal
      return new Promise(() => undefined)
    })
    const view = renderPage()

    view.unmount()

    expect(signal?.aborted).toBe(true)
  })

  it('refreshes the device list every 30 seconds and stops after unmount', async () => {
    vi.useFakeTimers()
    try {
      const view = renderPage()
      expect(deviceApi.listDevices).toHaveBeenCalledTimes(1)

      await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
      expect(deviceApi.listDevices).toHaveBeenCalledTimes(2)

      view.unmount()
      await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
      expect(deviceApi.listDevices).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })
})
