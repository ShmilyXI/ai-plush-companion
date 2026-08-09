import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from '../api/http'
import { registerAccount } from './register'

const PUBLIC_KEY = '043bdbcae97b96aa266d34821ce120b150634f070f12ba55323ef6b0757673aaa6eb058476b4bcc36c60191382b0bd61083724055ed78f81b6af9ff1cd6925c006'

describe('registerAccount', () => {
  beforeEach(() => {
    vi.spyOn(http, 'get').mockResolvedValue({
      data: { code: 0, data: { sm2PublicKey: PUBLIC_KEY } },
    })
  })

  it('encrypts the captcha and password before registration', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: { code: 0, data: null } })

    await registerAccount({
      username: ' new-user ',
      password: 'ValidPass9!',
      captcha: 'abcde',
      captchaId: 'captcha-id',
    })

    expect(post).toHaveBeenCalledOnce()
    expect(post).toHaveBeenCalledWith('/user/register', {
      username: 'new-user',
      password: expect.stringMatching(/^04[0-9a-f]+$/i),
      captchaId: 'captcha-id',
    })
    const payload = post.mock.calls[0][1] as { password: string }
    expect(payload.password).not.toContain('ValidPass9!')
  })
})
