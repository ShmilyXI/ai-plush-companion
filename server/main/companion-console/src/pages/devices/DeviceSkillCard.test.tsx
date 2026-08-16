import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as api from '../../api/capabilities'
import { DeviceSkillCard } from './DeviceSkillCard'

vi.mock('../../api/capabilities', async () => {
  const actual = await vi.importActual<typeof import('../../api/capabilities')>('../../api/capabilities')
  return { ...actual, listDeviceSkillCatalog: vi.fn(), listDeviceSkills: vi.fn(), saveDeviceSkills: vi.fn() }
})

const catalog: api.DeviceSkillCatalogItem[] = [{
  skillId: 'skill-weather', name: '天气查询', description: '查询天气', publishedVersion: 2,
  versions: [1, 2], overridableFields: ['location'], defaults: { location: '上海' },
  available: true, unavailableReason: null,
}, {
  skillId: 'skill-news', name: '新闻查询', description: '查询新闻', publishedVersion: 1,
  versions: [1], overridableFields: [], defaults: {}, available: true, unavailableReason: null,
}, {
  skillId: 'skill-brightness', name: '亮度调节', description: null, publishedVersion: 1,
  versions: [1], overridableFields: ['brightness'], defaults: { brightness: 50 },
  available: false, unavailableReason: '设备未上报工具 self.screen.set_brightness',
}]

const binding: api.DeviceSkillBinding = {
  skillId: 'skill-weather', skillName: '天气查询', versionMode: 'LATEST', fixedVersion: null,
  resolvedVersion: 2, enabled: true, overrides: { location: '杭州' }, triggerPriority: 10, configVersion: 8,
}

describe('DeviceSkillCard', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(api.listDeviceSkillCatalog).mockResolvedValue(catalog)
    vi.mocked(api.listDeviceSkills).mockResolvedValue([binding])
    vi.mocked(api.saveDeviceSkills).mockResolvedValue([binding])
  })

  it('binds skills, selects fixed versions, edits allowed overrides, and saves once', async () => {
    render(<DeviceSkillCard deviceId="device-1" />)

    expect(await screen.findByRole('checkbox', { name: '绑定 天气查询' })).toBeChecked()
    expect(screen.getByText('设备未上报工具 self.screen.set_brightness')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: '绑定 亮度调节' })).toBeDisabled()

    await userEvent.click(screen.getByRole('switch', { name: '启用 天气查询' }))
    await userEvent.click(screen.getByRole('combobox', { name: '天气查询版本策略' }))
    await userEvent.click(await screen.findByText('固定版本'))
    await userEvent.click(screen.getByRole('combobox', { name: '天气查询固定版本' }))
    await userEvent.click(await screen.findByText('v1'))
    const location = screen.getByLabelText('天气查询 location')
    await userEvent.clear(location)
    await userEvent.type(location, '北京')
    await userEvent.click(screen.getByRole('checkbox', { name: '绑定 新闻查询' }))
    await userEvent.click(screen.getByRole('button', { name: /保\s*存设备能力/ }))

    await waitFor(() => expect(api.saveDeviceSkills).toHaveBeenCalledWith('device-1', [{
      skillId: 'skill-weather', versionMode: 'FIXED', fixedVersion: 1, enabled: false,
      overrides: { location: '北京' }, triggerPriority: 10,
    }, {
      skillId: 'skill-news', versionMode: 'LATEST', fixedVersion: null, enabled: true,
      overrides: {}, triggerPriority: 0,
    }]))
  })

  it('unbinds through the same transactional save', async () => {
    vi.mocked(api.saveDeviceSkills).mockResolvedValue([])
    render(<DeviceSkillCard deviceId="device-1" />)

    await userEvent.click(await screen.findByRole('checkbox', { name: '绑定 天气查询' }))
    await userEvent.click(screen.getByRole('button', { name: /保\s*存设备能力/ }))

    expect(api.saveDeviceSkills).toHaveBeenCalledWith('device-1', [])
  })

  it('keeps edits visible when saving fails', async () => {
    vi.mocked(api.saveDeviceSkills).mockRejectedValue(new Error('设备能力保存失败'))
    render(<DeviceSkillCard deviceId="device-1" />)

    const location = await screen.findByLabelText('天气查询 location')
    await userEvent.clear(location)
    await userEvent.type(location, '苏州')
    await userEvent.click(screen.getByRole('button', { name: /保\s*存设备能力/ }))

    expect(await screen.findByText('设备能力保存失败')).toBeInTheDocument()
    expect(location).toHaveValue('苏州')
  })
})
