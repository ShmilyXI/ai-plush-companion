import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CompanionWebView } from './CompanionWebView'

describe('CompanionWebView', () => {
  it('waits for the iframe to load before requesting and posting a bootstrap code', async () => {
    const request = vi.fn().mockResolvedValue({ code: 'bootstrap-1' })
    render(<CompanionWebView appUrl="https://companion.example.com" requestBootstrap={request} />)
    expect(screen.getByTitle('陪伴对话')).toHaveAttribute('src', 'https://companion.example.com')
    expect(request).not.toHaveBeenCalled()
    screen.getByTitle('陪伴对话').dispatchEvent(new Event('load'))
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1))
  })

  it('rejects bootstrap messages from another origin', async () => {
    const request = vi.fn().mockResolvedValue({ code: 'bootstrap-1' })
    render(<CompanionWebView appUrl="https://companion.example.com" requestBootstrap={request} />)
    screen.getByTitle('陪伴对话').dispatchEvent(new Event('load'))
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1))
    window.dispatchEvent(new MessageEvent('message', { origin: 'https://evil.example.com', data: { type: 'companion.ready' } }))
    expect(request).toHaveBeenCalledTimes(1)
  })
})
