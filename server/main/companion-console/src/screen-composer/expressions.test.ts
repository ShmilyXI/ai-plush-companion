import { describe, expect, it } from 'vitest'
import { emotionIdFor, expressionName } from './expressions'

describe('expressions', () => {
  it('reads the bare name from a resource path', () => {
    expect(expressionName('assets/happy.eaf')).toBe('happy')
    expect(expressionName('sad')).toBe('sad')
    expect(expressionName(undefined)).toBe('neutral')
  })

  it('maps known names and falls back to idle for unknown ones', () => {
    expect(emotionIdFor('assets/thinking.eaf')).toBe('30')
    expect(emotionIdFor('assets/unknown.eaf')).toBe('02')
  })
})
