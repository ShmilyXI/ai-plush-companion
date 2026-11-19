import { NextResponse } from 'next/server'
import type { ManagerResult } from './server-manager'

/** 把 manager-api 的结果转成给浏览器的统一格式：成功是 { data }，失败是 { error, code }。 */
export function respond<T>(result: ManagerResult<T>, fallback: string, map?: (data: T | null) => unknown) {
  if (!result.ok) {
    return NextResponse.json({ error: result.message || fallback, code: result.code }, { status: result.status })
  }
  return NextResponse.json({ data: map ? map(result.data) : result.data })
}
