import { afterEach, describe, expect, it } from 'vitest'
import { isCastId, isListedShape } from '@mowa-bot/engine'
import { emotionHops, mowaLook, mowaState, parseReplyEmotion, SPEAKING_STATE } from './mowa'
import { LOOK_COLORS, setCustomLook } from './roles'

afterEach(() => { setCustomLook('小试', null) })

describe('mowaState', () => {
  it('follows the call state when the character is not speaking', () => {
    expect(mowaState('offline', 'happy')).toBe('sleeping')
    expect(mowaState('hearing', 'happy')).toBe('listening')
    expect(mowaState('thinking', 'sad')).toBe('thinking')
    expect(mowaState('idle', 'happy')).toBe('idle')
  })

  it('shows the reply emotion while speaking', () => {
    expect(mowaState('speaking', 'happy')).toBe('happy')
    expect(mowaState('speaking', 'sad')).toBe('sad')
    expect(mowaState('speaking', 'surprised')).toBe('surprised')
    expect(mowaState('speaking', 'sleepy')).toBe('drowsy')
    expect(mowaState('speaking', 'concerned')).toBe('curious')
    expect(mowaState('speaking', 'gentle')).toBe('happy')
  })

  it('speaks with the sound wave when the reply has no emotion', () => {
    expect(mowaState('speaking', null)).toBe(SPEAKING_STATE)
    expect(mowaState('speaking', 'neutral')).toBe(SPEAKING_STATE)
  })

  it('wakes, gets bored and then drowsy while nothing happens', () => {
    expect(mowaState('idle', null, false, 'waking')).toBe('waking')
    expect(mowaState('idle', null, false, 'bored')).toBe('bored')
    expect(mowaState('idle', null, false, 'drowsy')).toBe('drowsy')
    // 情绪还在的那几秒里，情绪优先；其他通话状态不受闲着的阶段影响。
    expect(mowaState('idle', 'happy', true, 'bored')).toBe('happy')
    expect(mowaState('hearing', null, false, 'drowsy')).toBe('listening')
  })

  it('keeps the emotion for a while after the reply ends', () => {
    expect(mowaState('idle', 'sad', true)).toBe('sad')
    expect(mowaState('idle', 'neutral', true)).toBe('idle')
    expect(mowaState('idle', null, true)).toBe('idle')
  })
})

describe('parseReplyEmotion', () => {
  it('accepts the server emotions and nothing else', () => {
    expect(parseReplyEmotion('gentle')).toBe('gentle')
    expect(parseReplyEmotion('angry')).toBeNull()
    expect(parseReplyEmotion(undefined)).toBeNull()
  })
})

it('hops only for happy and surprised replies', () => {
  expect(emotionHops('happy')).toBe(true)
  expect(emotionHops('surprised')).toBe(true)
  expect(emotionHops('sad')).toBe(false)
  expect(emotionHops(null)).toBe(false)
})

describe('mowaLook', () => {
  it('draws the preset rabbit as a lilac bunny', () => {
    const look = mowaLook('紫萱')
    expect(look.cast).toBeUndefined()
    expect(look.shape).toBe('bunny')
    expect(look.ink).toMatchObject({ to: LOOK_COLORS.find((item) => item.key === 'lilac')!.plush.fur })
  })

  it('uses the cast member the user picked, with the animal as a fallback', () => {
    setCustomLook('小试', { kind: 'cat', color: 'mint', mowa: 'hop' })
    const look = mowaLook('小试')
    expect(look.cast).toBe('hop')
    expect(look.shape).toBe('cat')
  })

  it('only maps animals to shapes the engine knows', () => {
    for (const kind of ['rabbit', 'cat', 'bear'] as const) {
      setCustomLook('小试', { kind, color: 'honey' })
      expect(isListedShape(mowaLook('小试').shape)).toBe(true)
    }
    expect(isCastId('hop')).toBe(true)
  })
})
