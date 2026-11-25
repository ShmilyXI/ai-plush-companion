import { act, render, screen } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { afterEach, describe, expect, it } from 'vitest'
import MowaCastPicker from './MowaCastPicker'
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

it('lists the cast with static thumbnails and reports the pick', () => {
  const picked: string[] = []
  render(<MowaCastPicker value="hop" onChange={(id) => picked.push(id)} />)
  const items = screen.getAllByRole('radio')
  expect(items.length).toBeGreaterThan(60)
  expect(screen.getByRole('radio', { name: '跳跳' })).toHaveAttribute('aria-checked', 'true')
  screen.getByRole('radio', { name: '熊熊' }).click()
  expect(picked).toEqual(['bruno'])
})
