import { act, fireEvent, render, screen } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ReplyBubble from './ReplyBubble'

// jsdom 没有 PointerEvent。用 MouseEvent 代替，按键和坐标才能传进去。
if (typeof window.PointerEvent === 'undefined') (window as unknown as { PointerEvent: typeof MouseEvent }).PointerEvent = MouseEvent

describe('ReplyBubble', () => {
  afterEach(() => { vi.useRealTimers() })

  it('opens the menu on a long press without also copying, and closes it on Escape', () => {
    vi.useFakeTimers()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const replay = vi.fn()
    render(<ReplyBubble text="晚安。" onReplay={replay} />)
    const bubble = screen.getByRole('button', { name: '晚安。' })

    fireEvent.pointerDown(bubble, { button: 0, clientX: 10, clientY: 10 })
    act(() => { vi.advanceTimersByTime(500) })
    fireEvent.pointerUp(bubble)
    fireEvent.click(bubble)
    expect(screen.getByRole('menu')).toBeVisible()
    expect(writeText).not.toHaveBeenCalled()

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()

    // 长按之后的下一次点按照常复制。
    fireEvent.pointerDown(bubble, { button: 0, clientX: 10, clientY: 10 })
    fireEvent.pointerUp(bubble)
    fireEvent.click(bubble)
    expect(writeText).toHaveBeenCalledWith('晚安。')
  })

  it('does nothing when the finger moves, because that is scrolling', () => {
    vi.useFakeTimers()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    render(<ReplyBubble text="晚安。" />)
    const bubble = screen.getByRole('button', { name: '晚安。' })

    fireEvent.pointerDown(bubble, { button: 0, clientX: 10, clientY: 10 })
    fireEvent.pointerMove(bubble, { clientX: 10, clientY: 40 })
    act(() => { vi.advanceTimersByTime(500) })
    fireEvent.pointerUp(bubble)
    fireEvent.click(bubble)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(writeText).not.toHaveBeenCalled()
  })
})
