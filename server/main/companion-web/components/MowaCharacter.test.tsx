import { act, fireEvent, render, screen } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { afterEach, describe, expect, it } from 'vitest'
import MowaLookPicker from './MowaLookPicker'
import type { CustomLook } from '../lib/roles'
import MowaCharacter from './MowaCharacter'
import { setCustomLook } from '../lib/roles'

afterEach(() => { setCustomLook('小试', null) })

describe('MowaCharacter', () => {
  it('draws the role and follows the reply emotion while speaking', async () => {
    const { container, rerender } = render(<MowaCharacter name="紫萱" mood="idle" />)
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    expect(container.querySelector('.character.mowa svg')).not.toBeNull()

    rerender(<MowaCharacter name="紫萱" mood="speaking" emotion="sad" level={() => 0.5} />)
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    expect(container.querySelector('.character.mowa.mood-speaking svg')).not.toBeNull()
  })

  it('falls back to the animal when the saved cast member does not exist', async () => {
    setCustomLook('小试', { kind: 'cat', color: 'blue', mowa: 'nobody' })
    const { container } = render(<MowaCharacter name="小试" mood="idle" />)
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    expect(container.querySelector('.character.mowa svg')).not.toBeNull()
  })
})

it('lists the cast, styles, textures and faces, and reports each pick', () => {
  const picked: CustomLook[] = []
  const look: CustomLook = { kind: 'bear', color: 'honey', mowa: 'hop' }
  render(<MowaLookPicker look={look} onChange={(next) => picked.push(next)} />)

  // 收起时只有前几个，加上选中的那个（跳跳不在前面）。展开后是全部。
  const cast = screen.getByRole('radiogroup', { name: 'Mowa Bot 角色' })
  expect(cast.querySelectorAll('[role="radio"]').length).toBe(16)
  expect(screen.getByRole('radio', { name: '跳跳' })).toHaveAttribute('aria-checked', 'true')
  fireEvent.click(screen.getByRole('button', { name: /展开全部/ }))
  expect(cast.querySelectorAll('[role="radio"]').length).toBeGreaterThan(60)
  // 没挑过画风和质感时是矢量、扁平；没挑过脸型时是角色自己的脸。
  expect(screen.getByRole('radio', { name: '矢量' })).toHaveAttribute('aria-checked', 'true')
  expect(screen.getByRole('radio', { name: '扁平' })).toHaveAttribute('aria-checked', 'true')
  expect(screen.getByRole('radio', { name: '原样' })).toHaveAttribute('aria-checked', 'true')

  screen.getByRole('radio', { name: '熊熊' }).click()
  screen.getByRole('radio', { name: '像素' }).click()
  screen.getByRole('radio', { name: '水彩' }).click()
  screen.getByRole('radio', { name: '闪亮眼' }).click()
  expect(picked).toEqual([
    { ...look, mowa: 'bruno' },
    { ...look, mowaStyle: 'pixel', mowaVariant: 'crisp' },
    { ...look, mowaStyle: 'vector', mowaVariant: 'watercolor' },
    { ...look, mowaFace: 'sparkle' },
  ])
})
