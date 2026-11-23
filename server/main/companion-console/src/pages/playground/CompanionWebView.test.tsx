import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CompanionWebView } from './CompanionWebView'

function ready(frame: HTMLIFrameElement, origin = 'https://companion.example.com') {
  window.dispatchEvent(new MessageEvent('message', { origin, source: frame.contentWindow, data: { type: 'companion.ready' } }))
}

describe('CompanionWebView', () => {
  it('waits for the web app to say it is ready before requesting and posting a sign-in code', async () => {
    const request = vi.fn().mockResolvedValue({ code: 'bootstrap-1' })
    render(<CompanionWebView appUrl="https://companion.example.com" requestBootstrap={request} />)
    const frame = screen.getByTitle('陪伴对话') as HTMLIFrameElement
    expect(frame).toHaveAttribute('src', 'https://companion.example.com')
    const post = vi.spyOn(frame.contentWindow!, 'postMessage')
    // 页面加载完不代表网页端已经在听消息了，这时不发。
    frame.dispatchEvent(new Event('load'))
    expect(request).not.toHaveBeenCalled()
    ready(frame)
    await waitFor(() => expect(post).toHaveBeenCalledWith({ type: 'companion.bootstrap', code: 'bootstrap-1' }, 'https://companion.example.com'))
    // 网页端在里面刷新后会再说一次，再给一个新的码。
    ready(frame)
    await waitFor(() => expect(request).toHaveBeenCalledTimes(2))
  })

  it('ignores ready messages from another origin', async () => {
    const request = vi.fn().mockResolvedValue({ code: 'bootstrap-1' })
    render(<CompanionWebView appUrl="https://companion.example.com" requestBootstrap={request} />)
    const frame = screen.getByTitle('陪伴对话') as HTMLIFrameElement
    ready(frame, 'https://evil.example.com')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(request).not.toHaveBeenCalled()
  })
})
