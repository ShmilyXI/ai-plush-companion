import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as deviceApi from '../api/devices'
import http from '../api/http'
import * as subscriptionApi from '../api/subscription'
import { DashboardPage } from './DashboardPage'

const device: deviceApi.CompanionDevice = {
  id: 'device /?#%',
  macAddress: 'AA:BB:CC:DD:EE:FF',
  alias: '书房伙伴',
  online: true,
  appVersion: '1.2.3',
  hasDisplay: true,
  hasCamera: false,
  activeProfileId: 'profile-1',
}

function renderPage() {
  return render(<MemoryRouter><DashboardPage /></MemoryRouter>)
}

describe('DashboardPage partial failures', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(subscriptionApi, 'getSubscription').mockResolvedValue({
      planId: 'basic', planCode: 'basic', planName: 'Basic', maxDevices: 1, maxProfiles: 3,
      longTermMemory: true, advancedVoice: false, expiresAt: '2027-07-31T12:00:00Z',
    })
    vi.spyOn(http, 'get').mockResolvedValue({
      data: { code: 0, msg: 'success', data: { list: [], total: 0 } },
      config: {},
    })
  })

  it('shows the active role, subscription, and latest conversations', async () => {
    vi.spyOn(deviceApi, 'listDevices').mockResolvedValue([device])
    vi.spyOn(deviceApi, 'listProfiles').mockResolvedValue([{ id: 'profile-1', name: '小智' }])
    vi.mocked(http.get).mockResolvedValue({
      data: {
        code: 0, msg: 'success', data: {
          list: [{ sessionId: 'session-1', title: '睡前聊聊', createdAt: '2026-07-31T20:00:00', chatCount: 6 }],
          total: 1,
        },
      },
      config: {},
    })

    renderPage()

    expect(await screen.findByText('当前角色')).toBeVisible()
    expect(screen.getByText('小智')).toBeVisible()
    expect(screen.getByText('Basic')).toBeVisible()
    expect(await screen.findByText('睡前聊聊')).toBeVisible()
    expect(http.get).toHaveBeenCalledWith('/agent/profile-1/sessions', expect.objectContaining({ params: { page: 1, limit: 3 } }))
  })

  it('keeps device facts visible when profiles fail', async () => {
    vi.spyOn(deviceApi, 'listDevices').mockResolvedValue([device])
    vi.spyOn(deviceApi, 'listProfiles').mockRejectedValue(new Error('角色统计加载失败'))

    renderPage()

    expect(await screen.findByText('书房伙伴')).toBeVisible()
    expect(screen.getByText('角色统计加载失败')).toBeVisible()
    expect(screen.getByText('不可用')).toBeVisible()
    expect(screen.getByRole('link', { name: /书房伙伴/ })).toHaveAttribute('href', `/devices/${encodeURIComponent(device.id)}`)
  })

  it('does not present a device failure as an empty device list', async () => {
    vi.spyOn(deviceApi, 'listDevices').mockRejectedValue(new Error('设备统计加载失败'))
    vi.spyOn(deviceApi, 'listProfiles').mockResolvedValue([{ id: 'profile-1', name: '小智' }])

    renderPage()

    expect(await screen.findByText('设备统计加载失败')).toBeVisible()
    expect(screen.queryByText('还没有绑定设备')).not.toBeInTheDocument()
    expect(screen.getAllByText('不可用')).toHaveLength(2)
    expect(screen.getByText('1')).toBeVisible()
  })
})
