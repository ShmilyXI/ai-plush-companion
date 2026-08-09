import { create } from 'zustand'

import http, { configureAuthBridge, type ApiResult } from '../api/http'
import { AuthProtocolError } from './errors'

export const SESSION_STORAGE_KEY = 'companion-console.session'

export interface CurrentUser {
  id: string
  username: string
  superAdmin: 0 | 1
  status: number
  token?: string
}

export interface AuthSession {
  token: string
  user: CurrentUser
}

interface LoginInput {
  username: string
  password: string
}

interface TokenData {
  token: string
  expire: number
  clientHash: string
}

interface PublicConfig {
  sm2PublicKey: string
  name?: string
}

export type AuthStatus = 'initializing' | 'authenticated' | 'anonymous'

interface AuthState {
  token: string | null
  user: CurrentUser | null
  permissions: string[]
  status: AuthStatus
  login: (input: LoginInput) => Promise<void>
  hydrate: () => Promise<void>
  refreshUser: () => Promise<CurrentUser>
  logout: () => Promise<void>
  clearSession: () => void
  hasPermission: (permission: string) => boolean
  setSessionForTest: (session: AuthSession) => void
  setTokenForTest: (token: string) => void
}

function validateToken(value: unknown): string {
  if (typeof value !== 'string' || !value.trim()) {
    throw new AuthProtocolError('登录响应缺少有效令牌')
  }
  return value.trim()
}

function normalizeUserId(value: unknown): string {
  if (typeof value === 'string' && /^[0-9]+$/.test(value)) return BigInt(value).toString()
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return String(value)
  throw new AuthProtocolError('用户信息响应格式无效')
}

function validateCurrentUser(value: unknown): CurrentUser {
  if (!value || typeof value !== 'object') {
    throw new AuthProtocolError('用户信息响应格式无效')
  }
  const user = value as Record<string, unknown>
  if (typeof user.username !== 'string'
    || !user.username.trim()
    || (user.superAdmin !== 0 && user.superAdmin !== 1)
    || typeof user.status !== 'number') {
    throw new AuthProtocolError('用户信息响应格式无效')
  }
  return {
    id: normalizeUserId(user.id),
    username: user.username.trim(),
    superAdmin: user.superAdmin,
    status: user.status,
    ...(typeof user.token === 'string' ? { token: user.token } : {}),
  }
}

export function readStoredToken(): string | null {
  try {
    const raw = localStorage.getItem(SESSION_STORAGE_KEY)
    if (!raw) return null
    const value = JSON.parse(raw) as { token?: unknown }
    const token = validateToken(value.token)
    return token
  } catch {
    localStorage.removeItem(SESSION_STORAGE_KEY)
    return null
  }
}

function permissionsFor(user: CurrentUser | null) {
  if (!user) return []
  return user.superAdmin === 1
    ? ['sys:role:normal', 'sys:role:superAdmin']
    : ['sys:role:normal']
}

function persistToken(token: string | null) {
  if (token) localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ token }))
  else localStorage.removeItem(SESSION_STORAGE_KEY)
}

async function fetchCurrentUser(token?: string): Promise<CurrentUser> {
  const response = token
    ? await http.get<ApiResult<CurrentUser>>('/user/info', {
        headers: { Authorization: `Bearer ${token}` },
      })
    : await http.get<ApiResult<CurrentUser>>('/user/info')
  return validateCurrentUser(response.data.data)
}

let authVersion = 0
let hydrationPromise: Promise<void> | null = null
const initialToken = readStoredToken()

export const useAuthStore = create<AuthState>((set, get) => ({
  token: initialToken,
  user: null,
  permissions: [],
  status: initialToken ? 'initializing' : 'anonymous',

  async login(input) {
    const version = ++authVersion
    persistToken(null)
    set({ token: null, user: null, permissions: [], status: 'anonymous' })
    try {
      const configResponse = await http.get<ApiResult<PublicConfig>>('/user/pub-config')
      const { encryptLoginPassword } = await import('./crypto')
      const encryptedPassword = encryptLoginPassword(
        configResponse.data.data?.sm2PublicKey,
        input.password,
      )
      const loginResponse = await http.post<ApiResult<TokenData>>('/user/login', {
        username: input.username,
        password: encryptedPassword,
      })
      const token = validateToken(loginResponse.data.data?.token)
      if (authVersion !== version) throw new AuthProtocolError('登录流程已取消')
      const user = await fetchCurrentUser(token)
      if (authVersion !== version) throw new AuthProtocolError('登录流程已取消')
      persistToken(token)
      set({ token, user, permissions: permissionsFor(user), status: 'authenticated' })
    } catch (error) {
      if (authVersion === version) {
        persistToken(null)
        set({ token: null, user: null, permissions: [], status: 'anonymous' })
      }
      throw error
    }
  },

  hydrate() {
    if (get().status !== 'initializing' || !get().token) return Promise.resolve()
    if (hydrationPromise) return hydrationPromise
    const version = authVersion
    const token = get().token
    hydrationPromise = (async () => {
      try {
        const user = await get().refreshUser()
        if (authVersion !== version || get().token !== token) return
        persistToken(token)
        set({ user, permissions: permissionsFor(user), status: 'authenticated' })
      } catch {
        if (authVersion === version && get().token === token) get().clearSession()
      } finally {
        hydrationPromise = null
      }
    })()
    return hydrationPromise
  },

  async refreshUser() {
    const user = await fetchCurrentUser()
    const token = get().token
    if (!token) throw new AuthProtocolError('登录状态已失效')
    set({ user, permissions: permissionsFor(user) })
    return user
  },

  async logout() {
    const token = get().token
    if (!token) {
      get().clearSession()
      return
    }
    const version = ++authVersion
    hydrationPromise = null
    try {
      await http.post('/user/logout')
    } catch {
      // The local session must still end when the network or server is unavailable.
    } finally {
      if (authVersion === version) get().clearSession()
    }
  },

  clearSession() {
    authVersion += 1
    hydrationPromise = null
    persistToken(null)
    set({ token: null, user: null, permissions: [], status: 'anonymous' })
  },

  hasPermission(permission) {
    return get().status === 'authenticated' && get().permissions.includes(permission)
  },

  setSessionForTest(session) {
    authVersion += 1
    const token = validateToken(session.token)
    const user = validateCurrentUser(session.user)
    persistToken(token)
    set({ token, user, permissions: permissionsFor(user), status: 'authenticated' })
  },

  setTokenForTest(tokenValue) {
    authVersion += 1
    const token = validateToken(tokenValue)
    persistToken(token)
    set({ token, user: null, permissions: [], status: 'initializing' })
  },
}))

configureAuthBridge({
  getToken: () => useAuthStore.getState().token,
  onUnauthorized: () => useAuthStore.getState().clearSession(),
})
