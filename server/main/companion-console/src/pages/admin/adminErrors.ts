import { message } from 'antd'

export function adminErrorMessage(reason: unknown, fallback: string) {
  const text = reason instanceof Error ? reason.message.trim() : ''
  if (!text || text.length > 160 || /(password|token|secret|api[_-]?key|密码|密钥)/i.test(text)) return fallback
  return text
}

export function reportAdminError(reason: unknown, fallback: string) {
  const text = adminErrorMessage(reason, fallback)
  message.error(text)
  return text
}
