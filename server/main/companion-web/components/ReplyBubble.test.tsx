import { act, fireEvent, render, screen } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ReplyBubble from './ReplyBubble'

// jsdom 没有 PointerEvent。用 MouseEvent 代替，按键和坐标才能传进去。
if (typeof window.PointerEvent === 'undefined') (window as unknown as { PointerEvent: typeof MouseEvent }).PointerEvent = MouseEvent

describe('ReplyBubble', () => {
  afterEach(() => { vi.useRealTimers() })

  it('opens the menu on a long press, and closes it on Escape or a tap outside', () => {
    vi.useFakeTimers()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const read = vi.fn()
    render(<ReplyBubble text="晚安。" onRead={read} />)
    const bubble = screen.getByRole('button', { name: '晚安。' })

    fireEvent.pointerDown(bubble, { button: 0, clientX: 10, clientY: 10 })
    act(() => { vi.advanceTimersByTime(500) })
    fireEvent.pointerUp(bubble)
    fireEvent.click(bubble)
    expect(screen.getByRole('menu')).toBeVisible()
    // 菜单放在页面最上层，不在消息列表里，不会被后面的消息盖住。
    expect(screen.getByRole('menu').parentElement).toBe(document.body)
    expect(writeText).not.toHaveBeenCalled()

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()

    // 菜单浮在遮罩上；点遮罩关掉，什么都不做。
    fireEvent.contextMenu(bubble)
    fireEvent.pointerDown(document.querySelector('.reply-overlay')!)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()

    fireEvent.contextMenu(bubble)
    fireEvent.click(screen.getByRole('menuitem', { name: '朗读' }))
    expect(read).toHaveBeenCalledTimes(1)
    expect(writeText).not.toHaveBeenCalled()
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
