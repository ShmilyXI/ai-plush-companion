export function getSafeRedirect(search: string): string {
  const candidate = new URLSearchParams(search).get('redirect')
  if (!candidate || !candidate.startsWith('/') || candidate.startsWith('//')) return '/dashboard'
  let decoded: string
  try {
    decoded = decodeURIComponent(candidate)
  } catch {
    return '/dashboard'
  }
  const hasControlCharacter = [...decoded].some((character) => character.charCodeAt(0) < 32)
  if (decoded.includes('\\') || decoded.startsWith('//') || hasControlCharacter) return '/dashboard'
  const parsed = new URL(candidate, window.location.origin)
  if (parsed.origin !== window.location.origin || !parsed.pathname.startsWith('/')) return '/dashboard'
  return `${parsed.pathname}${parsed.search}${parsed.hash}`
}
