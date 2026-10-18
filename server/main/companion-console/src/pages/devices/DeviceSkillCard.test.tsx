import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as api from '../../api/capabilities'
import { DeviceSkillCard } from './DeviceSkillCard'

vi.mock('../../api/capabilities', async () => {
  const actual = await vi.importActual<typeof import('../../api/capabilities')>('../../api/capabilities')
  return { ...actual, listDeviceSkillCatalog: vi.fn(), listDeviceSkills: vi.fn() }
})

const catalog: api.DeviceSkillCatalogItem[] = [{
  skillId: 'skill-weather', name: '天气查询', description: '查询天气', publishedVersion: 2,
  packageVersion: 2, packageSha256: 'a'.repeat(64), packageSource: 'MIGRATION',
  versions: [1, 2], overridableFields: ['location'], defaults: { location: '上海' },
  available: true, unavailableReason: null,
}, {
  skillId: 'skill-brightness', name: '亮度调节', description: null, publishedVersion: 1,
  packageVersion: 1, packageSha256: 'c'.repeat(64), packageSource: 'MIGRATION',
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
  })

  it('shows the effective projection without device-level edit controls', async () => {
    render(<DeviceSkillCard deviceId="device-1" />)

    expect(await screen.findByText('有效设备能力')).toBeInTheDocument()
    expect(screen.getByText('跟随智能体版本')).toBeInTheDocument()
    expect(screen.getByText('设备覆盖参数')).toBeInTheDocument()
    expect(screen.getByText('设备未上报工具 self.screen.set_brightness')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /保存设备能力/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('reports a request error without exposing edit actions', async () => {
    vi.mocked(api.listDeviceSkillCatalog).mockRejectedValue(new Error('设备能力加载失败'))
    render(<DeviceSkillCard deviceId="device-1" />)

    expect(await screen.findByText('设备能力加载失败')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /保存设备能力/ })).not.toBeInTheDocument()
  })
})
