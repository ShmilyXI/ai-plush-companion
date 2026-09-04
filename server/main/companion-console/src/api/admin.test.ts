import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from './http'
import { cancelSubscription, clearAdminMemories, deleteAdminMemory, listAdminMemories, pauseSubscription, renameAdminDevice, unbindAdminDevice, updateAdminDeviceMode, updateAdminMemory, uploadFirmware } from './admin'

describe('admin firmware API', () => {
  beforeEach(() => {
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data: 'id' } })
  })

  it('sends the binary and metadata as multipart form data', async () => {
    const file = new File([new Uint8Array([1, 2, 3])], 'firmware.bin', { type: 'application/octet-stream' })
    await uploadFirmware(file, { firmwareName: '稳定版', type: 'esp32', version: '1.2.3', remark: 'release' })

    const body = vi.mocked(http.post).mock.calls[0][1] as FormData
    expect(body).toBeInstanceOf(FormData)
    expect(body.get('file')).toBe(file)
    expect(body.get('firmwareName')).toBe('稳定版')
    expect(body.get('version')).toBe('1.2.3')
  })
})

describe('administrator device mutations', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: null }, config: {} })
    vi.spyOn(http, 'delete').mockResolvedValue({ data: { code: 0, msg: 'success', data: null }, config: {} })
  })

  it('edits only the alias and unbinds through companion administrator routes', async () => {
    await renameAdminDevice('device/1', '床头伙伴')
    await unbindAdminDevice('device/1')

    expect(http.put).toHaveBeenCalledWith('/admin/companion/devices/device%2F1', { alias: '床头伙伴' })
    expect(http.delete).toHaveBeenCalledWith('/admin/companion/devices/device%2F1')
  })

  it('updates the companion mode through the dedicated route', async () => {
    await updateAdminDeviceMode('device/1', 'proactive')

    expect(http.put).toHaveBeenCalledWith('/admin/companion/devices/device%2F1/mode', { mode: 'proactive' })
  })
})

describe('administrator memory API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(http, 'get').mockResolvedValue({
      data: { code: 0, msg: 'success', data: [{ id: 'm1', content: '内容', updated_at: '2026-07-31T20:00:00Z', source_device_id: 'd1', source_profile_id: 'p1', sourceDeviceName: '设备', sourceProfileName: '紫萱' }] },
      config: {},
    })
    vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: null }, config: {} })
    vi.spyOn(http, 'delete').mockResolvedValue({ data: { code: 0, msg: 'success', data: null }, config: {} })
  })

  it('uses administrator routes and validates the returned memory fields', async () => {
    expect((await listAdminMemories('device/1'))[0]).toMatchObject({ id: 'm1', content: '内容', sourceProfileName: '紫萱' })
    await updateAdminMemory('device/1', 'memory/1', '新内容')
    await deleteAdminMemory('device/1', 'memory/1')
    await clearAdminMemories('device/1')

    expect(http.get).toHaveBeenCalledWith('/admin/companion/devices/device%2F1/memories', undefined)
    expect(http.put).toHaveBeenCalledWith('/admin/companion/devices/device%2F1/memories/memory%2F1', { content: '新内容' }, undefined)
    expect(http.delete).toHaveBeenCalledWith('/admin/companion/devices/device%2F1/memories/memory%2F1', undefined)
    expect(http.delete).toHaveBeenCalledWith('/admin/companion/devices/device%2F1/memories', undefined)
  })
})

describe('administrator subscription lifecycle API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: null }, config: {} })
  })

  it('pauses and cancels a user subscription through encoded administrator routes', async () => {
    await pauseSubscription('user/1')
    await cancelSubscription('user/1')

    expect(http.put).toHaveBeenCalledWith('/admin/companion/subscriptions/user%2F1/pause')
    expect(http.put).toHaveBeenCalledWith('/admin/companion/subscriptions/user%2F1/cancel')
  })
})
