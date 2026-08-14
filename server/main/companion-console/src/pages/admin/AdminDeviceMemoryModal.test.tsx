import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as adminApi from '../../api/admin'
import type { CompanionMemory } from '../../api/memories'
import { AdminDeviceMemoryModal } from './AdminDeviceMemoryModal'


const device: adminApi.AdminDevice = {
  id: 'device-a',
  alias: '床头伙伴',
  bindUserName: '小夏',
}
const memory: CompanionMemory = {
  id: 'memory-a',
  content: '用户喜欢海浪声。',
  updatedAt: '2026-08-14T10:00:00Z',
  sourceDeviceId: 'device-b',
  sourceProfileId: 'profile-a',
  sourceDeviceName: '书桌伙伴',
  sourceProfileName: '小满',
}


describe('AdminDeviceMemoryModal', () => {
  beforeEach(() => {
    vi.spyOn(adminApi, 'listAdminMemories').mockResolvedValue([memory])
  })

  it('explains that clearing affects the bound role shared by other devices', async () => {
    render(<AdminDeviceMemoryModal device={device} onClose={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '清空绑定角色记忆' }))

    expect(screen.getAllByText('会清空该用户在当前绑定角色下的长期记忆，包含其他设备共享的内容。').length).toBeGreaterThan(0)
  })
})
