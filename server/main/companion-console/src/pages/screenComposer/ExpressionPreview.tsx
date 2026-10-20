import { useEffect, useRef, useState } from 'react'
import { emotionIdFor, expressionName } from '../../screen-composer/expressions'
import { createExpression, type ExpressionInstance } from '../../screen-composer/expressionEngines'
import { engineOptions, type Appearance } from '../../screen-composer/appearance'

/** Live character face for an animation layer; the device plays a pre-rendered version of the same expression. */
export function ExpressionPreview({ resource, appearance }: { resource?: string; appearance: Appearance }) {
  const hostRef = useRef<HTMLDivElement>(null)
  const instanceRef = useRef<ExpressionInstance | null>(null)
  const latest = useRef({ emotionId: emotionIdFor(resource), appearance })
  const [error, setError] = useState<string | null>(null)
  const emotionId = emotionIdFor(resource)
  latest.current = { emotionId, appearance }

  // Anything the engine only reads at creation time rebuilds the instance; emotion, style and gaze are applied live below.
  const { character, shape, customColor, color, eyeColor, eyeScale, lite, idle } = appearance
  const createKey = JSON.stringify([character, shape, customColor, customColor ? [color, eyeColor] : null, eyeScale, lite, idle])

  useEffect(() => {
    let cancelled = false
    setError(null)
    const host = hostRef.current
    if (!host) return
    const { emotionId: initialEmotion, appearance: initial } = latest.current
    createExpression(initial.character, host, initialEmotion, engineOptions(initial)).then((instance) => {
      if (cancelled) { instance.destroy(); return }
      instanceRef.current = instance
      instance.setStyle({ sketch: latest.current.appearance.sketch ? 1 : 0 })
      instance.setGaze(latest.current.appearance.gazeX, latest.current.appearance.gazeY)
    }).catch((reason: unknown) => { if (!cancelled) setError(reason instanceof Error ? reason.message : '表情引擎加载失败') })
    return () => { cancelled = true; instanceRef.current?.destroy(); instanceRef.current = null; host.replaceChildren() }
  }, [createKey])

  useEffect(() => { instanceRef.current?.setEmotion(emotionId) }, [emotionId])
  useEffect(() => { instanceRef.current?.setStyle({ sketch: appearance.sketch ? 1 : 0 }) }, [appearance.sketch])
  useEffect(() => { instanceRef.current?.setGaze(appearance.gazeX, appearance.gazeY) }, [appearance.gazeX, appearance.gazeY])

  return <>
    {error && <span className="screen-composer-placeholder">{error}</span>}
    <div ref={hostRef} className="screen-composer-expression" title={expressionName(resource)} />
  </>
}
