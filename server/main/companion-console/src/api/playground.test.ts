import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as httpApi from './http'
import { createPlaygroundSession, getPlaygroundSession, sendPlaygroundInput } from './playground'

vi.mock('./http', async () => {
  const actual = await vi.importActual<typeof import('./http')>('./http')
  return { ...actual, default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() } }
})

describe('playground api', () => {
  beforeEach(() => vi.clearAllMocks())
  it('creates a session through the companion endpoint', async () => {
    vi.mocked(httpApi.default.post).mockResolvedValue({ data: { code: 0, msg: 'ok', data: { sessionId: 's', snapshotVersion: 1, effectiveConfig: {}, eventStreamPath: '/events', expiresAt: '' } } } as never)
    await expect(createPlaygroundSession({ profileId: 'p', virtualDevice: {} })).resolves.toMatchObject({ sessionId: 's' })
    expect(httpApi.default.post).toHaveBeenCalledWith('/companion/playground/sessions', expect.anything(), undefined)
  })
  it('URL encodes session ids when sending input', async () => {
    vi.mocked(httpApi.default.post).mockResolvedValue({ data: { code: 0, msg: 'ok', data: null } } as never)
    await sendPlaygroundInput('s /?', { kind: 'text', text: 'hello' })
    expect(httpApi.default.post).toHaveBeenCalledWith('/companion/playground/sessions/s%20%2F%3F/inputs', expect.anything(), undefined)
  })
  it('loads an existing session with an encoded id', async () => {
    vi.mocked(httpApi.default.get).mockResolvedValue({ data: { code: 0, msg: 'ok', data: { sessionId: 's', snapshotVersion: 1, effectiveConfig: {}, eventStreamPath: '/events', expiresAt: '' } } } as never)
    await expect(getPlaygroundSession('old /?')).resolves.toMatchObject({ sessionId: 's' })
    expect(httpApi.default.get).toHaveBeenCalledWith('/companion/playground/sessions/old%20%2F%3F', undefined)
  })
})
