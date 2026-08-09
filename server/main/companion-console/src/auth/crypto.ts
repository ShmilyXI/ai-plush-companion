import { sm2 } from 'sm-crypto'

import { AuthProtocolError } from './errors'

const SM2_PUBLIC_KEY_PATTERN = /^(?:04)?[0-9a-f]{128}$/i
const sm2Runtime = sm2 as typeof sm2 & {
  verifyPublicKey: (publicKey: string) => boolean
}

export function validateSm2PublicKey(value: unknown): string {
  if (typeof value !== 'string') {
    throw new AuthProtocolError('服务端登录公钥格式无效')
  }
  const publicKey = value.trim()
  if (!SM2_PUBLIC_KEY_PATTERN.test(publicKey)) {
    throw new AuthProtocolError('服务端登录公钥格式无效')
  }
  const normalizedPublicKey = publicKey.length === 128 ? `04${publicKey}` : publicKey
  let isValid = false
  try {
    isValid = sm2Runtime.verifyPublicKey(normalizedPublicKey)
  } catch {
    // Invalid curve points must surface as a stable protocol error.
  }
  if (!isValid) {
    throw new AuthProtocolError('服务端登录公钥格式无效')
  }
  return normalizedPublicKey
}

export function encryptPassword(publicKey: unknown, captcha: string, password: string): string {
  const validPublicKey = validateSm2PublicKey(publicKey)
  const cipher = sm2.doEncrypt(`${captcha}${password}`, validPublicKey, 1)
  if (!/^[0-9a-f]+$/i.test(cipher)) {
    throw new AuthProtocolError('登录凭据加密失败')
  }
  return `04${cipher}`
}

export function encryptLoginPassword(publicKey: unknown, password: string): string {
  const validPublicKey = validateSm2PublicKey(publicKey)
  const cipher = sm2.doEncrypt(password, validPublicKey, 1)
  if (!/^[0-9a-f]+$/i.test(cipher)) {
    throw new AuthProtocolError('登录凭据加密失败')
  }
  return `04${cipher}`
}
