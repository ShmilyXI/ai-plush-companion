import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { configureAuthBridge, createHttpClient } from './http'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

afterEach(() => {
  configureAuthBridge({ getToken: () => null, onUnauthorized: () => undefined })
})

describe('HTTP permission errors', () => {
  it('turns a bare HTTP 403 into an explicit administrator permission error', async () => {
    const client = createHttpClient()
    client.defaults.adapter = async (config) => {
      throw new AxiosError('Request failed with status code 403', 'ERR_BAD_RESPONSE', config, undefined, {
        config,
        data: null,
        headers: new AxiosHeaders(),
        status: 403,
        statusText: 'Forbidden',
      })
    }

    await expect(client.get('/admin/companion/plans')).rejects.toMatchObject({
      code: 403,
      message: '没有管理员权限',
    })
  })

  it('normalizes a structured HTTP 403 before using its backend message', async () => {
    const client = createHttpClient()
    client.defaults.adapter = async (config) => {
      throw new AxiosError('Forbidden', 'ERR_BAD_RESPONSE', config, undefined, {
        config,
        data: { code: 500, msg: '内部权限异常', data: null },
        headers: new AxiosHeaders(),
        status: 403,
        statusText: 'Forbidden',
      })
    }
    await expect(client.get('/admin/companion/plans')).rejects.toMatchObject({ code: 403, message: '没有管理员权限' })
  })
})

describe('HTTP authentication expiry', () => {
  it('does not clear a newer session for a stale business 401 with mixed-case bearer headers', async () => {
    let currentToken: string | null = null
    const onUnauthorized = vi.fn()
    configureAuthBridge({ getToken: () => currentToken, onUnauthorized })
    const release = deferred<void>()
    const requested = deferred<void>()
    const client = createHttpClient()
    client.defaults.adapter = async (config) => {
      requested.resolve()
      await release.promise
      return {
        config,
        data: { code: 401, msg: '旧登录已过期', data: null },
        headers: new AxiosHeaders(),
        status: 200,
        statusText: 'OK',
      }
    }

    const request = client.get('/user/info', {
      headers: { authorization: '  bEaReR   old-token  ' },
    })
    await requested.promise
    currentToken = 'new-token'
    release.resolve()

    await expect(request).rejects.toMatchObject({ code: 401 })
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('does not clear a newer session for a stale HTTP 401', async () => {
    let currentToken: string | null = null
    const onUnauthorized = vi.fn()
    configureAuthBridge({ getToken: () => currentToken, onUnauthorized })
    const release = deferred<void>()
    const requested = deferred<void>()
    const client = createHttpClient()
    client.defaults.adapter = async (config) => {
      requested.resolve()
      await release.promise
      throw new AxiosError('Unauthorized', 'ERR_BAD_RESPONSE', config, undefined, {
        config,
        data: null,
        headers: new AxiosHeaders(),
        status: 401,
        statusText: 'Unauthorized',
      })
    }

    const request = client.get('/user/info', {
      headers: { Authorization: 'Bearer old-token' },
    })
    await requested.promise
    currentToken = 'new-token'
    release.resolve()

    await expect(request).rejects.toBeInstanceOf(AxiosError)
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('clears the current session for its own business and HTTP 401 responses', async () => {
    const onUnauthorized = vi.fn()
    configureAuthBridge({ getToken: () => 'current-token', onUnauthorized })
    const businessClient = createHttpClient()
    businessClient.defaults.adapter = async (config) => ({
      config,
      data: { code: 401, msg: '登录已过期', data: null },
      headers: new AxiosHeaders(),
      status: 200,
      statusText: 'OK',
    })
    const networkClient = createHttpClient()
    networkClient.defaults.adapter = async (config) => {
      throw new AxiosError('Unauthorized', 'ERR_BAD_RESPONSE', config, undefined, {
        config,
        data: null,
        headers: new AxiosHeaders(),
        status: 401,
        statusText: 'Unauthorized',
      })
    }

    await expect(businessClient.get('/user/info')).rejects.toMatchObject({ code: 401 })
    await expect(networkClient.get('/user/info')).rejects.toBeInstanceOf(AxiosError)

    expect(onUnauthorized).toHaveBeenCalledTimes(2)
  })
})
