import { CHARACTERS, DEFAULT_CHARACTER, type CharacterId } from './expressionEngines'

export const BALL_SHAPES = [
  { id: 'blob', label: '圆胖' },
  { id: 'wedge', label: '三角' },
  { id: 'gem', label: '菱形' },
] as const
export type BallShape = typeof BALL_SHAPES[number]['id']

/** Preview-only look of the expression character. The device plays pre-rendered animations, so this is not part of the scene schema. */
export interface Appearance {
  character: CharacterId
  shape: BallShape
  customColor: boolean
  color: string
  eyeColor: string
  eyeScale: number
  sketch: boolean
  lite: boolean
  idle: boolean
  gazeX: number
  gazeY: number
}

export const DEFAULT_APPEARANCE: Appearance = {
  character: DEFAULT_CHARACTER, shape: 'blob', customColor: false, color: '#f4eee2', eyeColor: '#1b1b1b',
  eyeScale: 1, sketch: false, lite: false, idle: false, gazeX: 0, gazeY: 0,
}

export const EYE_SCALE_RANGE = { min: 0.6, max: 2, step: 0.1 } as const
export const APPEARANCE_STORAGE_KEY = 'screen-composer-appearance'

const HEX = /^#[0-9a-fA-F]{6}$/
const clamp = (value: unknown, min: number, max: number, fallback: number) =>
  typeof value === 'number' && Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : fallback

/** Accept anything from storage; keep valid fields, fall back to defaults for the rest. */
export function sanitizeAppearance(raw: unknown): Appearance {
  const input = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const d = DEFAULT_APPEARANCE
  return {
    character: CHARACTERS.some((item) => item.id === input.character) ? input.character as CharacterId : d.character,
    shape: BALL_SHAPES.some((item) => item.id === input.shape) ? input.shape as BallShape : d.shape,
    customColor: input.customColor === true,
    color: typeof input.color === 'string' && HEX.test(input.color) ? input.color : d.color,
    eyeColor: typeof input.eyeColor === 'string' && HEX.test(input.eyeColor) ? input.eyeColor : d.eyeColor,
    eyeScale: clamp(input.eyeScale, EYE_SCALE_RANGE.min, EYE_SCALE_RANGE.max, d.eyeScale),
    sketch: input.sketch === true,
    lite: input.lite === true,
    idle: input.idle === true,
    gazeX: clamp(input.gazeX, -1, 1, 0),
    gazeY: clamp(input.gazeY, -1, 1, 0),
  }
}

/**
 * Options for EmotionBall.create / MoodMates.create. The engines only theme the body when `color`
 * is set (eyes default to white then), and only the ball has a `shape`.
 */
export function engineOptions(appearance: Appearance): Record<string, unknown> {
  const options: Record<string, unknown> = {
    eyeScale: appearance.eyeScale, lite: appearance.lite, idle: appearance.idle,
  }
  if (appearance.character === 'ball') options.shape = appearance.shape
  if (appearance.customColor) { options.color = appearance.color; options.eyeColor = appearance.eyeColor }
  return options
}

export function loadAppearance(): Appearance {
  try {
    const stored = localStorage.getItem(APPEARANCE_STORAGE_KEY)
    if (stored) return sanitizeAppearance(JSON.parse(stored))
    // Earlier builds only remembered the character.
    return sanitizeAppearance({ character: localStorage.getItem('screen-composer-character') })
  } catch { return DEFAULT_APPEARANCE }
}

export function saveAppearance(appearance: Appearance) {
  try { localStorage.setItem(APPEARANCE_STORAGE_KEY, JSON.stringify(appearance)) } catch { /* preference only */ }
}
