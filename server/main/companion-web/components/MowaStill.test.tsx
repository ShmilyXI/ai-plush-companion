import { render, waitFor } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { afterEach, expect, it } from 'vitest'
import Avatar from './Avatar'
import RoleFigure from './RoleFigure'
import { setCustomLook } from '../lib/roles'

afterEach(() => { setCustomLook('小试', null) })

it('draws the avatar and the figure as the picked Mowa Bot character', async () => {
  setCustomLook('小试', { kind: 'bear', color: 'honey', mowa: 'hop' })
  const avatar = render(<Avatar name="小试" size={30} />)
  const figure = render(<RoleFigure name="小试" size={120} />)

  const still = await waitFor(() => {
    const element = avatar.container.querySelector('img.mowa-still')
    expect(element).not.toBeNull()
    return element!
  })
  expect(still.getAttribute('src')).toMatch(/^data:image\/svg\+xml,/)
  await waitFor(() => expect(figure.container.querySelector('img.mowa-still')).not.toBeNull())
})

it('keeps the drawn picture for roles without a Mowa Bot pick', () => {
  setCustomLook('小试', { kind: 'bear', color: 'honey' })
  const { container } = render(<Avatar name="小试" size={30} />)
  const picture = container.querySelector('img')
  expect(picture).not.toHaveClass('mowa-still')
  expect(picture?.getAttribute('src')).toContain('/roles/bear-face')
})
