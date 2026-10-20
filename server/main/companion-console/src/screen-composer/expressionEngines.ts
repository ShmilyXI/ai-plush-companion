// The expression engines are plain-script libraries (window.EmotionBall / window.MoodMates).
// Each engine reads window.EMOTION_SEED while its own engine.js loads, and both ship a different
// emotions.js, so an engine's scripts must load in order and one engine at a time (see `queue`).
export type CharacterId = 'ball' | 'nimbo' | 'twinkle'

export const CHARACTERS: { id: CharacterId; label: string }[] = [
  { id: 'ball', label: '球球' },
  { id: 'nimbo', label: '云宝' },
  { id: 'twinkle', label: '亮亮' },
]
export const DEFAULT_CHARACTER: CharacterId = 'ball'

export interface ExpressionInstance {
  setEmotion: (id: string) => void
  setStyle: (style: { sketch: number }) => void
  setGaze: (x: number, y: number) => void
  spin: (turns: number) => void
  burst: (count: number) => void
  bounce: () => void
  destroy: () => void
}
interface EngineApi { create: (container: HTMLElement, options: Record<string, unknown>) => ExpressionInstance }

const BALL_SCRIPTS = ['rings', 'emotions', 'ball', 'engine'].map((name) => `/vendor/emotion-ball/${name}.js`)
const MATES_CORE = ['geometry', 'render', 'features', 'fx', 'emotions', 'engine'].map((name) => `/vendor/mood-mates/${name}.js`)

const ENGINES = {
  ball: { scripts: BALL_SCRIPTS, global: 'EmotionBall', options: { shape: 'blob' } },
  nimbo: { scripts: [...MATES_CORE, '/vendor/mood-mates/nimbo.js'], global: 'MoodMates', options: { character: 'nimbo' } },
  twinkle: { scripts: [...MATES_CORE, '/vendor/mood-mates/twinkle.js'], global: 'MoodMates', options: { character: 'twinkle' } },
} as const

const loaded = new Set<string>()
let queue: Promise<unknown> = Promise.resolve()

function loadScript(src: string) {
  if (loaded.has(src)) return Promise.resolve()
  return new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = src
    script.onload = () => { loaded.add(src); resolve() }
    script.onerror = () => reject(new Error(`加载失败：${src}`))
    document.head.appendChild(script)
  })
}

/** Load the scripts a character needs (serialized across engines) and return its engine. */
function loadEngine(id: CharacterId): Promise<EngineApi> {
  const engine = ENGINES[id]
  const run = queue.then(async () => {
    // nimbo and twinkle share the Mood Mates core; only the first load runs it.
    for (const src of engine.scripts) await loadScript(src)
    const api = (window as unknown as Record<string, EngineApi | undefined>)[engine.global]
    // A server that falls back to index.html for unknown paths "loads" the script without defining the engine.
    if (typeof api?.create !== 'function') throw new Error(`表情引擎未加载，请确认 ${engine.scripts[0].replace(/[^/]*$/, '')} 下的脚本可以访问（需要重启开发服务或重新构建）`)
    return api
  })
  queue = run.catch(() => undefined)
  return run
}

const live = new Set<ExpressionInstance>()

export type Effect = 'spin' | 'burst' | 'bounce'

/** Trigger a one-off effect on every expression currently shown (used by the preview's effect buttons). */
export function playEffect(effect: Effect) {
  live.forEach((instance) => {
    if (effect === 'spin') instance.spin(2)
    else if (effect === 'burst') instance.burst(24)
    else instance.bounce()
  })
}

export async function createExpression(id: CharacterId, host: HTMLElement, emotionId: string, options: Record<string, unknown> = {}): Promise<ExpressionInstance> {
  const api = await loadEngine(id)
  const instance = api.create(host, { ...ENGINES[id].options, ...options, emotion: emotionId })
  live.add(instance)
  const destroy = instance.destroy.bind(instance)
  instance.destroy = () => { live.delete(instance); destroy() }
  return instance
}
