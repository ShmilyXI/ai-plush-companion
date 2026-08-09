import http from './http'

export interface ChangePasswordInput {
  password: string
  newPassword: string
}

export async function changePassword(input: ChangePasswordInput) {
  await http.put('/user/change-password', input)
}
