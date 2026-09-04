import type { PlaygroundSession, PlaygroundStore } from './playgroundTypes'

export const playgroundStorageKey = 'zixuan.playground.v1'
const maxSessions = 30
const emptyStore = (): PlaygroundStore => ({ version: 1, sessions: [], activeSessionId: null, updatedAt: new Date().toISOString() })

function stripAudio(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripAudio)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([key]) => key !== 'audioBlob' && key !== 'audioDataUrl').map(([key, item]) => [key, stripAudio(item)]))
  return value
}

function validStore(value: unknown): value is PlaygroundStore {
  return Boolean(value && typeof value === 'object' && (value as PlaygroundStore).version === 1 && Array.isArray((value as PlaygroundStore).sessions))
}

export function loadPlaygroundStore(): PlaygroundStore {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(playgroundStorageKey) || '')
    return validStore(parsed) ? parsed : emptyStore()
  } catch { return emptyStore() }
}

export function savePlaygroundStore(store: PlaygroundStore) {
  const sanitized = stripAudio(store) as PlaygroundStore
  localStorage.setItem(playgroundStorageKey, JSON.stringify(sanitized))
}

export function upsertSession(session: PlaygroundSession) {
  const store = loadPlaygroundStore()
  const sessions = [session, ...store.sessions.filter((item) => item.id !== session.id)].slice(0, maxSessions)
  savePlaygroundStore({ version: 1, sessions, activeSessionId: session.id, updatedAt: new Date().toISOString() })
}

export function removeSession(id: string) {
  const store = loadPlaygroundStore()
  const sessions = store.sessions.filter((item) => item.id !== id)
  savePlaygroundStore({ ...store, sessions, activeSessionId: store.activeSessionId === id ? sessions[0]?.id ?? null : store.activeSessionId, updatedAt: new Date().toISOString() })
}
