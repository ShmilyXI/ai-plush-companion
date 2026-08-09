import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from './http'
import {
  getDevice,
  listDevices,
  listProfiles,
  sendDeviceCommand,
  switchDeviceProfile,
  unbindDevice,
  updateDevice,
} from './devices'

const validDevice = {
  id: 'device-a',
  macAddress: 'AA:BB:CC:DD:EE:FF',
  alias: '书房伙伴',
  online: true,
  appVersion: '1.2.3',
  hasDisplay: true,
  hasCamera: false,
  activeProfileId: 'profile-1',
}

describe('device API protocol validation', () => {
  beforeEach(() => {
    vi.spyOn(http, 'get')
  })

  it('normalizes numeric device and profile ids at the network boundary', async () => {
    vi.mocked(http.get)
      .mockResolvedValueOnce({ data: { code: 0, msg: 'success', data: [{ ...validDevice, id: 17, activeProfileId: 29 }] } })
      .mockResolvedValueOnce({ data: { code: 0, msg: 'success', data: [{ id: 29, name: '小智' }] } })

    await expect(listDevices()).resolves.toMatchObject([{ id: '17', activeProfileId: '29' }])
    await expect(listProfiles()).resolves.toEqual([{ id: '29', name: '小智' }])
  })

  it.each([
    null,
    {},
    [{ ...validDevice, online: 'true' }],
    [{ ...validDevice, hasDisplay: 1 }],
    [{ ...validDevice, hasCamera: null }],
    [{ ...validDevice, id: null }],
  ])('rejects malformed device list payload %#', async (data) => {
    vi.mocked(http.get).mockResolvedValue({ data: { code: 0, msg: 'success', data } })

    await expect(listDevices()).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it('rejects malformed detail and profile payloads', async () => {
    vi.mocked(http.get)
      .mockResolvedValueOnce({ data: { code: 0, msg: 'success', data: { ...validDevice, macAddress: null } } })
      .mockResolvedValueOnce({ data: { code: 0, msg: 'success', data: [{ id: 'profile-1', name: null }] } })

    await expect(getDevice('device-a')).rejects.toMatchObject({ name: 'ApiProtocolError' })
    await expect(listProfiles()).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it.each([
    { code: '0', msg: 'success', data: [] },
    { code: 0, msg: 7, data: [] },
    { code: 0, msg: 'success' },
  ])('rejects a malformed success envelope %# as a protocol error', async (payload) => {
    vi.mocked(http.get).mockResolvedValue({ data: payload })

    await expect(listDevices()).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })
})

describe('device API path encoding', () => {
  it('encodes every id as one path segment', async () => {
    const rawId = 'device /?#%'
    const encoded = encodeURIComponent(rawId)
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: validDevice } })
    vi.spyOn(http, 'put').mockResolvedValue({ data: { code: 0, msg: 'success', data: null } })
    vi.spyOn(http, 'delete').mockResolvedValue({ data: { code: 0, msg: 'success', data: null } })
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data: true } })

    await getDevice(rawId)
    await updateDevice(rawId, { alias: '新名字' })
    await switchDeviceProfile(rawId, rawId)
    await unbindDevice(rawId)
    await sendDeviceCommand(rawId, 'volume', 50)

    expect(http.get).toHaveBeenCalledWith(`/companion/devices/${encoded}`, undefined)
    expect(http.put).toHaveBeenCalledWith(`/companion/devices/${encoded}`, { alias: '新名字' }, undefined)
    expect(http.put).toHaveBeenCalledWith(`/companion/devices/${encoded}/profile`, { profileId: rawId }, undefined)
    expect(http.delete).toHaveBeenCalledWith(`/companion/devices/${encoded}`, undefined)
    expect(http.post).toHaveBeenCalledWith(`/companion/devices/${encoded}/commands`, { command: 'volume', value: 50 }, undefined)
  })
})
