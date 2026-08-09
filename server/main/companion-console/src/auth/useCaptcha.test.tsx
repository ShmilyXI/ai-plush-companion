import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from '../api/http'
import { useCaptcha } from './useCaptcha'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function CaptchaHarness() {
  const { captcha, refresh } = useCaptcha()
  return <>
    <span data-testid="captcha-id">{captcha.id}</span>
    <span data-testid="captcha-url">{captcha.url}</span>
    <span data-testid="captcha-error">{captcha.error}</span>
    <button type="button" onClick={() => void refresh()}>refresh</button>
  </>
}

describe('useCaptcha', () => {
  beforeEach(() => {
    vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => `blob:${(blob as Blob).size}`)
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
  })

  it('keeps the newest captcha and releases its URL on unmount', async () => {
    const first = deferred<{ data: Blob }>()
    const second = deferred<{ data: Blob }>()
    vi.spyOn(http, 'get')
      .mockImplementationOnce(() => first.promise as never)
      .mockImplementationOnce(() => second.promise as never)
    const view = render(<CaptchaHarness />)

    fireEvent.click(screen.getByRole('button', { name: 'refresh' }))
    second.resolve({ data: new Blob(['new-captcha']) })
    await waitFor(() => expect(screen.getByTestId('captcha-url')).toHaveTextContent('blob:11'))

    first.resolve({ data: new Blob(['old']) })
    await Promise.resolve()
    expect(screen.getByTestId('captcha-url')).toHaveTextContent('blob:11')

    view.unmount()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:11')
  })
})
