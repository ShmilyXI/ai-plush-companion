/**
 * Device/server expression names -> Mood Mates emotionId.
 * The names are the ones EmotionPolicy sends to the device (`display_emotion`) and the device
 * plays as local animations; the ids are the stable Mood Mates ids (00-09 lifecycle, 10-29
 * emotions, 30-49 agent states). Keep this table in step with EmotionPolicy._MAP.
 */
export const EXPRESSIONS = [
  { name: 'neutral', label: '平静', emotionId: '02' },
  { name: 'happy', label: '开心', emotionId: '10' },
  { name: 'relaxed', label: '温柔', emotionId: '19' },
  { name: 'sad', label: '失落', emotionId: '12' },
  { name: 'surprised', label: '惊讶', emotionId: '13' },
  { name: 'sleepy', label: '困倦', emotionId: '00' },
  { name: 'thinking', label: '思考', emotionId: '30' },
] as const

export type ExpressionName = typeof EXPRESSIONS[number]['name']

const FALLBACK_ID = '02'

/** "assets/happy.eaf" or "happy" -> "happy" (the form the server publishes to the device). */
export function expressionName(resource: string | undefined): string {
  return (resource || '').replace(/^.*\//, '').replace(/\.eaf$/, '') || 'neutral'
}

export function emotionIdFor(resource: string | undefined): string {
  const name = expressionName(resource)
  return EXPRESSIONS.find((item) => item.name === name)?.emotionId ?? FALLBACK_ID
}
