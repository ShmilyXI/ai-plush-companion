import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { importSkillPackage } from '../../api/capabilities'
import { SkillPackageImportModal } from './SkillPackageImportModal'

vi.mock('../../api/capabilities', async () => {
  const actual = await vi.importActual<typeof import('../../api/capabilities')>('../../api/capabilities')
  return { ...actual, importSkillPackage: vi.fn() }
})

describe('SkillPackageImportModal', () => {
  it('uploads a package and shows validation plus mapped metadata', async () => {
    vi.mocked(importSkillPackage).mockResolvedValue({
      capabilityId: 'skill-weather', name: '天气查询', version: 2,
      packageSha256: 'a'.repeat(64), packageSize: 2048,
      manifest: { id: 'skill-weather', tools: [{ type: 'PLUGIN', ref: 'plugin-weather', name: 'get_weather' }] },
      skillMarkdown: '# Weather', validation: { status: 'VALID', issues: [] },
    })
    const onImported = vi.fn()
    const onFileSelected = vi.fn()
    render(<SkillPackageImportModal open onCancel={vi.fn()} onImported={onImported} onFileSelected={onFileSelected} />)

    const file = new File(['zip'], 'weather.skill.zip', { type: 'application/zip' })
    await userEvent.upload(screen.getByLabelText('Skill 包'), file)

    expect(await screen.findByText('校验通过')).toBeInTheDocument()
    expect(screen.getByText('天气查询')).toBeInTheDocument()
    expect(screen.getByText('skill-weather')).toBeInTheDocument()
    expect(screen.getByText('v2')).toBeInTheDocument()
    expect(screen.getByText('PLUGIN / plugin-weather / get_weather')).toBeInTheDocument()
    expect(onImported).toHaveBeenCalledWith(expect.objectContaining({ capabilityId: 'skill-weather' }))
    await userEvent.click(screen.getByRole('button', { name: '保存为草稿' }))
    expect(onFileSelected).toHaveBeenCalledWith(expect.objectContaining({ capabilityId: 'skill-weather' }), file)
  })
})
