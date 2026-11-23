import { afterEach, describe, expect, it, vi } from 'vitest'
import http from '../api/http'
import { companionWebUrl, openCompanionWeb } from './companionWeb'

describe('openCompanionWeb', () => {
  afterEach(() => { vi.restoreAllMocks() })

  it('opens the window on the click and then points it at the web app with a one-time code', async () => {
    const opened = { opener: window, location: { href: 'about:blank' } }
    const open = vi.spyOn(window, 'open').mockReturnValue(opened as unknown as Window)
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, data: { code: 'one-time' } } })

    await openCompanionWeb('http://127.0.0.1:8010')

    // 窗口在点击的那一刻打开，免得被当成弹窗拦掉。
    expect(open).toHaveBeenCalledWith('about:blank', '_blank')
    expect(post).toHaveBeenCalledWith('/api/v1/web-sessions/bootstrap', { audience: 'companion-web', origin: 'http://127.0.0.1:8010' })
    expect(opened.location.href).toBe('http://127.0.0.1:8010/?code=one-time')
    expect(opened.opener).toBeNull()
  })

  it('still opens the web app when no code can be had, where the user can sign in', async () => {
    const opened = { opener: window, location: { href: 'about:blank' } }
    vi.spyOn(window, 'open').mockReturnValue(opened as unknown as Window)
    vi.spyOn(http, 'post').mockRejectedValue(new Error('network'))

    await openCompanionWeb('http://127.0.0.1:8010')

    expect(opened.location.href).toBe('http://127.0.0.1:8010/')
  })
})

describe('companionWebUrl', () => {
  const at = (href: string) => new URL(href) as unknown as Location

  it('follows the console host on this machine, so the embedded web app keeps its sign-in cookie', () => {
    expect(companionWebUrl('http://127.0.0.1:8010', at('http://localhost:8001/web'))).toBe('http://localhost:8010')
    expect(companionWebUrl('http://localhost:8010', at('http://127.0.0.1:8001/web'))).toBe('http://127.0.0.1:8010')
  })

  it('leaves a real address alone', () => {
    expect(companionWebUrl('https://app.example.com', at('https://console.example.com/web'))).toBe('https://app.example.com')
    expect(companionWebUrl('http://127.0.0.1:8010', at('http://192.168.1.20:8001/web'))).toBe('http://127.0.0.1:8010')
  })
})
