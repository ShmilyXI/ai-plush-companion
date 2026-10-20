import { render, screen } from '@testing-library/react'
import { describe, expect, it, beforeEach } from 'vitest'
import { ScreenComposerPage } from './ScreenComposerPage'

describe('ScreenComposerPage', () => {
  beforeEach(() => localStorage.clear())

  it('renders the variable canvas and all five display modes', () => {
    render(<ScreenComposerPage />)
    expect(screen.getByText('屏幕编排器')).toBeInTheDocument()
    expect(screen.getByText('画布预览')).toBeInTheDocument()
    expect(screen.getAllByRole('spinbutton')[0]).toHaveValue('360')
    expect(screen.getByText('显示模式')).toBeInTheDocument()
  })

  it('starts with one expression layer and no touch regions', () => {
    render(<ScreenComposerPage />)
    expect(screen.getByText('表情 · 平静')).toBeInTheDocument()
    expect(screen.getByText('暂无触摸热区')).toBeInTheDocument()
  })
})
