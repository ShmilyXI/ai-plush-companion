import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { ProfileCapabilitiesTab } from './ProfileCapabilitiesTab'

describe('ProfileCapabilitiesTab', () => {
  it('edits Agent Skill bindings without exposing device-level ownership controls', async () => {
    const onChangeSkills = vi.fn()
    const user = userEvent.setup()
    render(<ProfileCapabilitiesTab onChangeSkills={onChangeSkills} skills={[{
      skillId: 'skill-weather', versionMode: 'LATEST', fixedVersion: null,
      overrideJson: null, triggerPriority: 10, enabled: true,
    }]} />)

    await user.click(screen.getByRole('button', { name: /添加 Skill/ }))
    expect(onChangeSkills).toHaveBeenCalledWith(expect.arrayContaining([
      expect.objectContaining({ skillId: 'skill-weather' }),
      expect.objectContaining({ skillId: '', versionMode: 'LATEST', enabled: true }),
    ]))
    expect(screen.getByRole('button', { name: /删除 Skill/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /保存设备能力/ })).not.toBeInTheDocument()
  })
})
