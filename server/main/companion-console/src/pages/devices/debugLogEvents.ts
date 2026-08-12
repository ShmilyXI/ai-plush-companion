import type { DebugLogEvent } from '../../api/deviceDebugLogs'

function compareDecimal(left: string, right: string) {
  const normalizedLeft = left.replace(/^0+(?=\d)/, '')
  const normalizedRight = right.replace(/^0+(?=\d)/, '')
  return normalizedLeft.length - normalizedRight.length || normalizedLeft.localeCompare(normalizedRight)
}

function compareRedisCursor(left: string, right: string) {
  const leftParts = /^(\d+)-(\d+)$/.exec(left)
  const rightParts = /^(\d+)-(\d+)$/.exec(right)
  if (!leftParts || !rightParts) return left.localeCompare(right)
  return compareDecimal(leftParts[1], rightParts[1]) || compareDecimal(leftParts[2], rightParts[2])
}

export function mergeDebugEvents(current: Iterable<DebugLogEvent>, incoming: Iterable<DebugLogEvent>) {
  const merged = new Map(Array.from(current, (event) => [event.cursor, event]))
  for (const event of incoming) merged.set(event.cursor, event)
  return Array.from(merged.values()).sort((left, right) => (
    left.receivedAt - right.receivedAt || compareRedisCursor(left.cursor, right.cursor)
  )).slice(-1_000)
}

export function newestDebugCursor(events: DebugLogEvent[], fallback: string) {
  return events.reduce(
    (current, event) => compareRedisCursor(event.cursor, current) > 0 ? event.cursor : current,
    fallback,
  )
}

export function advancesDebugCursor(candidate: string, current: string) {
  return compareRedisCursor(candidate, current) > 0
}
