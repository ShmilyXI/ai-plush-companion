import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { afterEach, describe, expect, it } from 'vitest'
import MowaLookPicker from './MowaLookPicker'
import Pet from './Pet'
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

describe('poking', () => {
  it('hands back a poke that the character can take', async () => {
    const pokeRef: { current: (() => void) | null } = { current: null }
    render(<MowaCharacter name="紫萱" mood="idle" pokeRef={pokeRef} />)
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    expect(typeof pokeRef.current).toBe('function')
    expect(() => pokeRef.current?.()).not.toThrow()
  })

  it('pokes an enlarged Mowa Bot pet instead of shrinking it, and leaves drawn roles as they were', async () => {
    const closed: string[] = []
    setCustomLook('小试', { kind: 'cat', color: 'blue', mowa: 'hop' })
    const mowa = render(<Pet name="小试" mood="idle" level={() => 0} open onOpen={() => {}} onClose={() => closed.push('小试')} />)
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    expect(mowa.queryByRole('button', { name: '缩小小试' })).toBeNull()
    fireEvent.click(mowa.getByRole('button', { name: '戳一下小试' }))
    expect(closed).toEqual([])
    mowa.unmount()

    const drawn = render(<Pet name="紫萱" mood="idle" level={() => 0} open onOpen={() => {}} onClose={() => closed.push('紫萱')} />)
    fireEvent.click(drawn.getByRole('button', { name: '缩小紫萱' }))
    expect(closed).toEqual(['紫萱'])
  })

  it('offers a poke on an enlarged Live2D role, and shrinks it instead while the model cannot be drawn', async () => {
    // 年糕的默认装扮（主播装）是定制的 Live2D 模型。测试环境没有 WebGL，模型画不出来，只有静态图。
    const closed: string[] = []
    const pet = render(<Pet name="年糕" mood="idle" level={() => 0} open onOpen={() => {}} onClose={() => closed.push('年糕')} />)
    expect(pet.queryByRole('button', { name: '缩小年糕' })).toBeNull()
    await waitFor(() => expect(document.querySelector('.character.live2d.failed')).not.toBeNull())
    fireEvent.click(pet.getByRole('button', { name: '戳一下年糕' }))
    expect(closed).toEqual(['年糕'])
  })
})
