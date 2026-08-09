import { sm2 } from 'sm-crypto'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { AuthProtocolError } from './errors'
import { encryptLoginPassword, encryptPassword, validateSm2PublicKey } from './crypto'

const PUBLIC_KEY = '043bdbcae97b96aa266d34821ce120b150634f070f12ba55323ef6b0757673aaa6eb058476b4bcc36c60191382b0bd61083724055ed78f81b6af9ff1cd6925c006'
const sm2Runtime = sm2 as typeof sm2 & {
  verifyPublicKey: (publicKey: string) => boolean
}

describe('SM2 browser encryption', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('encrypts only the password for login', () => {
    vi.spyOn(sm2, 'doEncrypt').mockReturnValue('a1b2c3')

    const cipher = encryptLoginPassword(PUBLIC_KEY, 'secret')

    expect(sm2.doEncrypt).toHaveBeenCalledWith('secret', PUBLIC_KEY, 1)
    expect(cipher).toBe('04a1b2c3')
  })

  it('normalizes an unprefixed valid public key', () => {
    expect(validateSm2PublicKey(PUBLIC_KEY.slice(2))).toBe(PUBLIC_KEY)
  })

  it('rejects a well-formed point that is not on the SM2 curve', () => {
    expect(() => validateSm2PublicKey(`04${'0'.repeat(128)}`))
      .toThrow(AuthProtocolError)
    expect(() => validateSm2PublicKey(`04${'0'.repeat(128)}`))
      .toThrow('服务端登录公钥格式无效')
  })

  it('does not leak public-key parser errors', () => {
    vi.spyOn(sm2Runtime, 'verifyPublicKey').mockImplementation(() => {
      throw new TypeError('curve parser failed')
    })

    expect(() => validateSm2PublicKey(PUBLIC_KEY)).toThrow(AuthProtocolError)
    expect(() => validateSm2PublicKey(PUBLIC_KEY)).not.toThrow(TypeError)
  })

  it('encrypts login passwords with the real SM2 implementation', () => {
    const cipher = encryptLoginPassword(PUBLIC_KEY.slice(2), 'secret')

    expect(cipher).toMatch(/^04[0-9a-f]+$/i)
    expect(cipher).toHaveLength(206)
  })

  it('produces the legacy 04-prefixed C1C3C2 cipher with a real public key', () => {
    const cipher = encryptPassword(PUBLIC_KEY, 'abcde', 'secret')

    expect(cipher).toMatch(/^04[0-9a-f]+$/i)
    expect(cipher).toHaveLength(216)
  })

  it('rejects a malformed server public key with a stable error', () => {
    expect(() => encryptPassword('not-a-public-key', 'abcde', 'secret'))
      .toThrow('服务端登录公钥格式无效')
  })
})
