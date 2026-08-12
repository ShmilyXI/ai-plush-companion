import axios, {
  AxiosError,
  type AxiosInstance,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'

export interface ApiResult<T> {
  code: number
  msg: string
  data: T
}

interface AuthBridge {
  getToken: () => string | null
  onUnauthorized: () => void
}

let authBridge: AuthBridge = {
  getToken: () => null,
  onUnauthorized: () => undefined,
}

export class ApiError extends Error {
  readonly code: number
  readonly data: unknown
  readonly config: InternalAxiosRequestConfig | undefined

  constructor(
    code: number,
    message: string,
    data?: unknown,
    config?: InternalAxiosRequestConfig,
  ) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.data = data
    this.config = config
  }
}

export function configureAuthBridge(bridge: AuthBridge) {
  authBridge = bridge
}

export function currentAuthorizationHeader(): string | null {
  const token = authBridge.getToken()
  return token ? `Bearer ${token}` : null
}

export function apiBaseUrl(): string {
  return import.meta.env.VITE_API_BASE_URL || '/xiaozhi'
}

function getAuthorizationHeader(config: InternalAxiosRequestConfig | undefined): string | null {
  const headers = config?.headers
  if (!headers) return null
  const headerGetter = (headers as { get?: (name: string) => unknown }).get
  if (typeof headerGetter === 'function') {
    const value = headerGetter.call(headers, 'Authorization')
    if (typeof value === 'string') return value
  }
  for (const [name, value] of Object.entries(headers)) {
    if (name.toLowerCase() === 'authorization' && typeof value === 'string') return value
  }
  return null
}

function bearerToken(authorization: string | null): string | null {
  if (!authorization) return null
  const match = /^\s*bearer\s+(.+?)\s*$/i.exec(authorization)
  return match?.[1]?.trim() || null
}

export function notifyUnauthorizedForToken(authorization: string | null) {
  const failedToken = bearerToken(authorization)
  if (failedToken === null || failedToken === authBridge.getToken()) authBridge.onUnauthorized()
}

function handleUnauthorized(config: InternalAxiosRequestConfig | undefined) {
  notifyUnauthorizedForToken(getAuthorizationHeader(config))
}

function isApiResult(value: unknown): value is ApiResult<unknown> {
  return Boolean(
    value
      && typeof value === 'object'
      && 'code' in value
      && typeof (value as { code: unknown }).code === 'number',
  )
}

function handleResult(response: AxiosResponse) {
  if (!isApiResult(response.data) || response.data.code === 0) {
    return response
  }

  if (response.data.code === 401) {
    handleUnauthorized(response.config)
  }

  return Promise.reject(new ApiError(
    response.data.code,
    response.data.msg || '请求失败',
    response.data.data,
    response.config,
  ))
}

function handleNetworkError(error: AxiosError<ApiResult<unknown>>) {
  if (error.response?.status === 401) {
    handleUnauthorized(error.config)
  }

  if (error.response?.status === 403) {
    return Promise.reject(new ApiError(403, '没有管理员权限', error.response.data, error.config))
  }

  if (isApiResult(error.response?.data)) {
    return Promise.reject(new ApiError(
      error.response.data.code,
      error.response.data.msg || error.message,
      error.response.data.data,
      error.config,
    ))
  }

  return Promise.reject(error)
}

export function createHttpClient(): AxiosInstance {
  const client = axios.create({
    baseURL: apiBaseUrl(),
    timeout: 30_000,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Accept-Language': 'zh-CN',
    },
  })

  client.interceptors.request.use((config) => {
    const authorization = currentAuthorizationHeader()
    if (authorization) {
      config.headers.Authorization = authorization
    }
    return config
  })
  client.interceptors.response.use(handleResult, handleNetworkError)
  return client
}

const http = createHttpClient()

export default http
