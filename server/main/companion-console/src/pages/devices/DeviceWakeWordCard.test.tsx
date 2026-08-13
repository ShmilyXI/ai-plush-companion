import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as devicesApi from '../../api/devices'
import { DeviceWakeWordCard } from './DeviceWakeWordCard'

const active = {
  desiredWord: '你好小智', desiredVersion: 1, activeWord: '你好小智', activeVersion: 1,
  status: 'ACTIVE' as const, lastErrorCode: null, lastErrorMessage: null,
  supported: true, unsupportedReason: null, updatedAt: null,
}

describe('DeviceWakeWordCard', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('validates, trims and saves a supported wake word', async () => {
    vi.spyOn(devicesApi, 'getDeviceWakeWord').mockResolvedValue(active)
    vi.spyOn(devicesApi, 'updateDeviceWakeWord').mockResolvedValue({ ...active, desiredWord: '小布小布', desiredVersion: 2, status: 'GENERATING' })
    render(<DeviceWakeWordCard deviceId="device-a" />)
    const input = await screen.findByLabelText('新唤醒词')
    fireEvent.change(input, { target: { value: ' 小布小布 ' } })
    fireEvent.click(screen.getByRole('button', { name: '保存并下发' }))
    await waitFor(() => expect(devicesApi.updateDeviceWakeWord).toHaveBeenCalledWith('device-a', '小布小布'))
    expect(await screen.findByText('正在生成资源')).toBeInTheDocument()
  })

  it.each(['小', '一二三四五六七八九', 'hello', '小布hello'])('rejects invalid word %s', async (word) => {
    vi.spyOn(devicesApi, 'getDeviceWakeWord').mockResolvedValue(active)
    const update = vi.spyOn(devicesApi, 'updateDeviceWakeWord')
    render(<DeviceWakeWordCard deviceId="device-a" />)
    fireEvent.change(await screen.findByLabelText('新唤醒词'), { target: { value: word } })
    fireEvent.click(screen.getByRole('button', { name: '保存并下发' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('只支持二到八个中文汉字')
    expect(update).not.toHaveBeenCalled()
  })

  it('shows unsupported reason without an input', async () => {
    vi.spyOn(devicesApi, 'getDeviceWakeWord').mockResolvedValue({ ...active, supported: false, unsupportedReason: '需要布局 2' })
    render(<DeviceWakeWordCard deviceId="device-a" />)
    expect(await screen.findByText('需要布局 2')).toBeInTheDocument()
    expect(screen.queryByLabelText('新唤醒词')).not.toBeInTheDocument()
  })

  it('retries a failed update and replaces local state', async () => {
    vi.spyOn(devicesApi, 'getDeviceWakeWord').mockResolvedValue({ ...active, status: 'FAILED', lastErrorMessage: '下载失败' })
    vi.spyOn(devicesApi, 'retryDeviceWakeWord').mockResolvedValue({ ...active, status: 'WAITING_DEVICE' })
    render(<DeviceWakeWordCard deviceId="device-a" />)
    expect(await screen.findByText('下载失败')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('等待设备上线')).toBeInTheDocument()
  })
})
