import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from '../../api/http'
import * as deviceApi from '../../api/devices'
import * as memoryApi from '../../api/memories'
import * as profileApi from '../../api/profiles'
import { MemoryPage } from './MemoryPage'

const device: deviceApi.CompanionDevice = {
  id: 'device-a', macAddress: 'AA', alias: '床头伙伴', online: true, appVersion: null,
  hasDisplay: false, hasCamera: false, activeProfileId: 'profile-a', debugLogEnabled: false,
}
const memory: memoryApi.CompanionMemory = {
  id: 'memory-a', content: '用户喜欢在睡前听海浪声。', updatedAt: '2026-07-29T10:00:00Z',
  sourceDeviceId: 'old-device', sourceProfileId: 'old-profile', sourceDeviceName: '旧设备', sourceProfileName: '旧角色',
}

function renderPage() {
  return render(<MemoryRouter><MemoryPage /></MemoryRouter>)
}

describe('memory API adapter', () => {
  it('rejects malformed memory payloads at runtime', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: [{ ...memory, updated_at: 7 }] } })
    await expect(memoryApi.listMemories('device-a')).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })

  it('encodes device and memory ids as path segments', async () => {
    vi.spyOn(http, 'delete').mockResolvedValue({ data: { code: 0, msg: 'success', data: null } })
    await memoryApi.deleteMemory('device /?#%', 'memory /?#%')
    expect(http.delete).toHaveBeenCalledWith(
      `/companion/devices/${encodeURIComponent('device /?#%')}/memories/${encodeURIComponent('memory /?#%')}`,
      undefined,
    )
  })
})

describe('MemoryPage', () => {
  beforeEach(() => {
    vi.spyOn(deviceApi, 'listDevices').mockResolvedValue([device])
    vi.spyOn(profileApi, 'listProfiles').mockResolvedValue([{ ...profileApi.emptyProfile, id: 'profile-a', name: '小满' }])
    vi.spyOn(memoryApi, 'listMemories').mockResolvedValue([memory])
  })

  it('exposes the page title as the main heading', async () => {
    renderPage()

    const heading = await screen.findByRole('heading', { level: 1, name: '记忆' })
    expect(heading.closest('.ant-pro-page-container')).not.toBeNull()
  })

  it('shows user-facing source information without provider names', async () => {
    renderPage()
    expect(await screen.findByText(memory.content)).toBeVisible()
    expect(screen.getByText('旧设备 · 旧角色')).toBeVisible()
    expect(screen.queryByText(/provider|namespace/i)).not.toBeInTheDocument()
  })

  it('keeps a memory row when deletion fails', async () => {
    vi.spyOn(memoryApi, 'deleteMemory').mockRejectedValue(new Error('记忆服务暂时不可用'))
    renderPage()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '删除这条记忆' }))
    await user.click(screen.getByRole('button', { name: '确认删除' }))
    expect(await screen.findByText('记忆服务暂时不可用')).toBeVisible()
    expect(screen.getByText(memory.content)).toBeVisible()
  })

  it('removes a memory only after the provider confirms deletion', async () => {
    let resolve!: () => void
    const pending = new Promise<void>((done) => { resolve = done })
    vi.spyOn(memoryApi, 'deleteMemory').mockReturnValue(pending)
    renderPage()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '删除这条记忆' }))
    await user.click(screen.getByRole('button', { name: '确认删除' }))
    expect(screen.getByText(memory.content)).toBeVisible()
    resolve()
    await waitFor(() => expect(screen.queryByText(memory.content)).not.toBeInTheDocument())
  })

  it('keeps original content when a correction fails', async () => {
    vi.spyOn(memoryApi, 'updateMemory').mockRejectedValue(new Error('修改失败'))
    renderPage()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '纠正这条记忆' }))
    const field = screen.getByLabelText('记忆内容')
    await user.clear(field)
    await user.type(field, '用户喜欢雨声。')
    await user.click(screen.getByRole('button', { name: '保存纠正' }))
    expect(await screen.findByText('修改失败')).toBeVisible()
    expect(screen.getByText(memory.content)).toBeVisible()
  })

  it('explains that clearing affects the shared role across devices', async () => {
    vi.spyOn(memoryApi, 'clearMemories').mockResolvedValue()
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '清空当前角色记忆' }))

    expect(screen.getAllByText('会清空该设备当前绑定角色的长期记忆。同一用户使用这个角色的其他设备也会受影响。').length).toBeGreaterThan(0)
    await user.click(screen.getByRole('button', { name: '确认清空' }))
    expect(await screen.findByText('该角色的长期记忆已清空')).toBeVisible()
  })
})
