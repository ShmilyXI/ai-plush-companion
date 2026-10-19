import { beforeEach, describe, expect, it, vi } from 'vitest'
import http from './http'
import { listScreenScenes, publishScreenScene, saveScreenScene } from './screenScenes'

const scene = { schemaVersion: 1 as const, sceneId: 'demo', version: 1, mode: 'custom' as const, canvas: { width: 360, height: 360, shape: 'circle' as const, safeInset: 24 }, layers: [], touchRegions: [], resources: {} }

describe('screen scene API', () => {
  beforeEach(() => vi.restoreAllMocks())
  it('loads device scenes and saves JSON drafts', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: [] }, config: {} } as never)
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data: { id: 's1' } }, config: {} } as never)
    await expect(listScreenScenes('device/1')).resolves.toEqual([])
    await expect(saveScreenScene('device/1', scene)).resolves.toEqual({ id: 's1' })
    expect(http.get).toHaveBeenCalledWith('/companion/screen-scenes/devices/device%2F1')
    expect(http.post).toHaveBeenCalledWith('/companion/screen-scenes', expect.objectContaining({ deviceId: 'device/1', sceneJson: JSON.stringify(scene) }))
  })
  it('publishes a saved scene version', async () => {
    vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, msg: 'success', data: { id: 's1', status: 'PUBLISHED' } }, config: {} } as never)
    await expect(publishScreenScene('s/1')).resolves.toEqual({ id: 's1', status: 'PUBLISHED' })
    expect(http.post).toHaveBeenCalledWith('/companion/screen-scenes/s%2F1/publish')
  })
})
