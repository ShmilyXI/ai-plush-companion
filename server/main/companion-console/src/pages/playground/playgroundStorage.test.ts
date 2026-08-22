import { beforeEach, describe, expect, it } from 'vitest'
import { loadPlaygroundStore, playgroundStorageKey, upsertSession } from './playgroundStorage'
import type { PlaygroundSession } from './playgroundTypes'

describe('playground storage', () => {
  beforeEach(() => localStorage.clear())
  it('recovers from malformed JSON', () => {
    localStorage.setItem(playgroundStorageKey, '{bad')
    expect(loadPlaygroundStore().sessions).toEqual([])
  })
  it('round trips and removes audio blobs', () => {
    const session = { id: 's', title: 'test', createdAt: new Date().toISOString(), playgroundSessionId: null, snapshot: {} as never, messages: [{ id: 'm', role: 'user' as const, text: 'x', createdAt: '', audioBlob: 'secret' }], events: [], screenState: {}, memories: [] } as unknown as PlaygroundSession
    upsertSession(session)
    const raw = localStorage.getItem(playgroundStorageKey)!
    expect(raw).not.toContain('audioBlob')
    expect(loadPlaygroundStore().sessions[0].id).toBe('s')
  })
  it('evicts oldest sessions', () => {
    for (let i = 0; i < 31; i += 1) upsertSession({ id: String(i), title: '', createdAt: '', playgroundSessionId: null, snapshot: {} as never, messages: [], events: [], screenState: {}, memories: [] } as PlaygroundSession)
    expect(loadPlaygroundStore().sessions).toHaveLength(30)
  })
})
