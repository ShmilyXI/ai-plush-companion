import http, { type ApiResult } from './http'
import type { ScreenScene } from '../screen-composer/sceneTypes'

export interface PublishedScreenScene extends ScreenScene {
  id: string
  deviceId: string
  status: 'DRAFT' | 'PUBLISHED'
}

function unwrap<T>(payload: ApiResult<T>) {
  if (payload.code !== 0) throw new Error(payload.msg || '请求失败')
  return payload.data
}

export async function listScreenScenes(deviceId: string) {
  const response = await http.get<ApiResult<PublishedScreenScene[]>>(`/companion/screen-scenes/devices/${encodeURIComponent(deviceId)}`)
  return unwrap(response.data)
}

export async function saveScreenScene(deviceId: string, scene: ScreenScene) {
  const response = await http.post<ApiResult<PublishedScreenScene>>('/companion/screen-scenes', {
    deviceId, sceneId: scene.sceneId, version: scene.version, mode: scene.mode, sceneJson: JSON.stringify(scene),
  })
  return unwrap(response.data)
}

export async function publishScreenScene(id: string) {
  const response = await http.post<ApiResult<PublishedScreenScene>>(`/companion/screen-scenes/${encodeURIComponent(id)}/publish`)
  return unwrap(response.data)
}

export async function uploadScreenImage(file: File, width = 360, height = 360) {
  const form = new FormData()
  form.append('file', file)
  form.append('width', String(width))
  form.append('height', String(height))
  const response = await http.post<ApiResult<{ id: string; asset: string; resource: string; url: string; width: number; height: number }>>('/companion/screen-scenes/resources', form)
  return unwrap(response.data)
}
