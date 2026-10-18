import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

if (typeof globalThis.localStorage === 'undefined') {
  const values = new Map<string, string>()
  const storage: Storage = {
    get length() {
      return values.size
    },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => Array.from(values.keys())[index] ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, String(value)),
  }
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage })
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'localStorage', { configurable: true, value: storage })
  }
}

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
})

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

Object.defineProperty(window, 'ResizeObserver', {
  writable: true,
  value: ResizeObserverStub,
})

Object.defineProperty(URL, 'createObjectURL', {
  configurable: true,
  writable: true,
  value: () => 'blob:test',
})

Object.defineProperty(URL, 'revokeObjectURL', {
  configurable: true,
  writable: true,
  value: () => undefined,
})

const getComputedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = (element: Element) => getComputedStyle(element)

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.restoreAllMocks()
})
