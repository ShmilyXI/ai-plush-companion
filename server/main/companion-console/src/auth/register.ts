import http, { type ApiResult } from '../api/http'
import { encryptPassword } from './crypto'

export interface RegisterInput {
  username: string
  password: string
  captcha: string
  captchaId: string
}

interface PublicConfig {
  sm2PublicKey: string
}

export async function registerAccount(input: RegisterInput): Promise<void> {
  const configResponse = await http.get<ApiResult<PublicConfig>>('/user/pub-config')
  const encryptedPassword = encryptPassword(
    configResponse.data.data?.sm2PublicKey,
    input.captcha,
    input.password,
  )
  await http.post('/user/register', {
    username: input.username.trim(),
    password: encryptedPassword,
    captchaId: input.captchaId,
  })
}
