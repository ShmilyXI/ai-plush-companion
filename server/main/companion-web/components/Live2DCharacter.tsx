'use client'

import { useEffect, useRef, useState, type MutableRefObject } from 'react'
import { live2dModel, roleLook } from '../lib/roles'
import type { CharacterMood } from './Character'

interface Live2DCharacterProps {
  name: string
  mood: CharacterMood
  /** 每一帧调用一次，返回 0 到 1 的音量：角色在说时是它自己声音的音量。 */
  level?: () => number
  /** 外面点了角色时调用 pokeRef.current() 戳它一下：摇头、蹦一下、吓一跳，每次换一种。模型没准备好时是 null。 */
  pokeRef?: MutableRefObject<(() => void) | null>
  /** 指针停在角色上：歪着头看你。 */
  emphasis?: boolean
}

/** Live2D 的运行库。它属于 Live2D Inc.，不打包进来，用 scripts/fetch-live2d.py 下载到 public/live2d/core/。 */
const CORE_URL = process.env.NEXT_PUBLIC_LIVE2D_CORE_URL || '/live2d/core/live2dcubismcore.min.js'

type Live2DWindow = Window & { Live2DCubismCore?: unknown; PIXI?: unknown }

let coreLoading: Promise<void> | null = null

/** 运行库只能用 <script> 加载，并且要在显示模型的库之前加载好。整个页面只加载一次。 */
function loadCore(): Promise<void> {
  const scope = window as Live2DWindow
  if (scope.Live2DCubismCore) return Promise.resolve()
  if (!coreLoading) {
    coreLoading = new Promise<void>((resolve, reject) => {
      const script = document.createElement('script')
      script.src = CORE_URL
      script.async = true
      script.onload = () => resolve()
      script.onerror = () => { coreLoading = null; reject(new Error('Live2D 运行库加载失败')) }
      document.head.appendChild(script)
    })
  }
  return coreLoading
}

/** 加载显示模型用的两个库。运行库必须先加载好，显示模型的库在加载时从全局找 PixiJS。 */
async function loadLibraries() {
  await loadCore()
  const PIXI = await import('pixi.js')
  ;(window as Live2DWindow).PIXI = PIXI
  const { Live2DModel } = await import('pixi-live2d-display/cubism4')
  return { PIXI, Live2DModel }
}

const warmed = new Set<string>()

/**
 * 提前把角色要用的东西下载好：运行库、两个库，以及这个角色的模型文件。
 *
 * 加载慢主要是因为这些东西加起来有好几 MB，而以前要等用户点了通话才开始下载。
 * 页面空闲时先下载好，点通话时就直接从浏览器缓存里拿。只下载、不显示，失败了也不要紧，到时候再正常加载。
 */
export function preloadLive2D(name: string) {
  if (typeof window === 'undefined' || typeof WebGLRenderingContext === 'undefined') return
  const url = live2dModel(roleLook(name).live2d).url
  if (warmed.has(url)) return
  warmed.add(url)
  void (async () => {
    await loadLibraries()
    const response = await fetch(url)
    const settings = await response.json() as { FileReferences?: Record<string, unknown> }
    const refs = settings.FileReferences ?? {}
    const files: string[] = []
    const add = (value: unknown) => { if (typeof value === 'string') files.push(value) }
    add(refs.Moc); add(refs.Physics); add(refs.Pose)
    if (Array.isArray(refs.Textures)) refs.Textures.forEach(add)
    // 待机动作一出来就要用，先下载；其他动作和表情用到时再说。
    const motions = refs.Motions as Record<string, Array<{ File?: string }>> | undefined
    for (const item of motions?.Idle ?? []) add(item.File)
    const base = url.slice(0, url.lastIndexOf('/') + 1)
    await Promise.all(files.map((file) => fetch(base + file).then((result) => result.arrayBuffer()).catch(() => undefined)))
  })().catch(() => { warmed.delete(url) })
}

/** 每种状态下头和眼睛的姿态，叠加在模型自己的待机动作上。单位是 Live2D 参数的单位：角度大约是度，眼球是 -1 到 1。 */
/** 戳一下的三种反应，轮着来。时长是秒。 */
const POKES = [
  { kind: 'shake', seconds: 0.9 },     // 摇头，眯一下眼
  { kind: 'hop', seconds: 0.55 },      // 蹦一下，头发跟着甩
  { kind: 'startle', seconds: 1.0 },   // 吓一跳：仰头、张嘴、眨两下眼
] as const
/** 两次戳之间至少隔多久，连点时太快的那一下不算。 */
const POKE_COOLDOWN = 0.6

const POSE: Record<CharacterMood, { angleX: number; angleY: number; angleZ: number; eyeX: number; eyeY: number }> = {
  idle: { angleX: 0, angleY: 0, angleZ: 0, eyeX: 0, eyeY: 0 },
  hearing: { angleX: 0, angleY: 6, angleZ: 9, eyeX: 0, eyeY: 0.1 },
  thinking: { angleX: -16, angleY: 10, angleZ: -5, eyeX: -0.6, eyeY: 0.6 },
  speaking: { angleX: 0, angleY: 0, angleZ: 0, eyeX: 0, eyeY: 0 },
  offline: { angleX: 0, angleY: -12, angleZ: 0, eyeX: 0, eyeY: -0.4 },
}

/**
 * 用 Live2D 模型画出来的角色。
 *
 * 模型自己会播待机动作、眨眼、呼吸。这里在它上面再叠几样：说话时嘴按自己声音的音量开合；
 * 听的时候歪头看着用户，想的时候往旁边上方看；开始说一段话时偶尔做个小动作；
 * 眼睛和头跟着指针转，指针停在上面时歪着头看你；被戳时摇头、蹦一下或吓一跳。
 *
 * 显示模型用的是 pixi-live2d-display（PixiJS 6），只在用到时才加载。运行库或模型加载失败时，显示角色的静态图片。
 */
export default function Live2DCharacter({ name, mood, level, pokeRef, emphasis = false }: Live2DCharacterProps) {
  const look = roleLook(name)
  const model = live2dModel(look.live2d)
  const mountRef = useRef<HTMLDivElement>(null)
  const moodRef = useRef(mood)
  moodRef.current = mood
  const levelRef = useRef(level)
  levelRef.current = level
  const emphasisRef = useRef(emphasis)
  emphasisRef.current = emphasis
  const pokeRefRef = useRef(pokeRef)
  pokeRefRef.current = pokeRef
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')

  useEffect(() => {
    const mount = mountRef.current
    if (!mount || typeof WebGLRenderingContext === 'undefined') { setState('failed'); return }
    let disposed = false
    let cleanup = () => undefined as void
    setState('loading')

    void (async () => {
      const { PIXI, Live2DModel } = await loadLibraries()
      if (disposed) return

      const canvas = document.createElement('canvas')
      canvas.className = 'character-canvas'
      mount.appendChild(canvas)
      const app = new PIXI.Application({
        view: canvas, backgroundAlpha: 0, antialias: true, autoDensity: true,
        resolution: Math.min(window.devicePixelRatio || 1, 2), width: mount.clientWidth || 1, height: mount.clientHeight || 1,
      })
      const live = await Live2DModel.from(model.url, { autoInteract: false })
      if (disposed) { live.destroy(); app.destroy(true); return }
      app.stage.addChild(live)

      // 模型原本的大小，用来按区域缩放。显示的是头到腰的上半身：全身太小，看不清表情。
      // 显示画布上哪一段由模型的 view 决定（见 lib/roles.ts 的 LIVE2D_MODELS）。
      const natural = { width: live.width, height: live.height }
      const view = model.view ?? { top: 0, height: 0.56 }
      live.anchor.set(0.5, 0)
      const fit = () => {
        const width = mount.clientWidth; const height = mount.clientHeight
        if (!width || !height) return
        app.renderer.resize(width, height)
        const scale = Math.min(height / (natural.height * view.height), (width * 1.15) / natural.width)
        live.scale.set(scale)
        live.position.set(width / 2, height * 0.03 - natural.height * view.top * scale)
        baseY = live.position.y; hopHeight = height * 0.06
      }
      let baseY = 0
      let hopHeight = 0
      fit()
      const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(fit) : null
      observer?.observe(mount)

      // 哪些参数控制嘴：模型里写了就用它写的，没写的按通常的名字找。
      const settings = live.internalModel.settings as { groups?: Array<{ Name?: string; name?: string; Ids?: string[]; ids?: string[] }> }
      const lipGroup = (settings.groups ?? []).find((group) => (group.Name ?? group.name) === 'LipSync')
      const lipIds = (lipGroup?.Ids ?? lipGroup?.ids ?? []).length ? (lipGroup?.Ids ?? lipGroup?.ids ?? []) : ['ParamMouthOpenY']

      const calm = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
      const pose = { angleX: 0, angleY: 0, angleZ: 0, eyeX: 0, eyeY: 0 }
      let smooth = 0
      let last = performance.now()
      let previousMood = moodRef.current
      const core = live.internalModel.coreModel as unknown as {
        setParameterValueById(id: string, value: number): void
        addParameterValueById(id: string, value: number): void
        getParameterValueById(id: string): number
      }
      // 物理（头发摆动）本来在这里的钩子之前算，看不到下面叠加的转头，头发就不跟着听、想时的姿态摆。
      // 把它拿出来，等姿态叠完再算一次。
      // 眼睛和头跟着指针（手机上是手指）转：指针相对角色脸的位置，换成 -1 到 1。
      const aim = { x: 0, y: 0 }
      const look = { x: 0, y: 0 }
      const onPointer = (event: PointerEvent) => {
        const rect = mount.getBoundingClientRect()
        if (!rect.width || !rect.height) return
        const cx = rect.left + rect.width / 2; const cy = rect.top + rect.height * 0.3
        aim.x = Math.max(-1, Math.min(1, (event.clientX - cx) / (rect.width * 0.8)))
        aim.y = Math.max(-1, Math.min(1, (event.clientY - cy) / (rect.height * 0.8)))
      }
      window.addEventListener('pointermove', onPointer, { passive: true })
      // 戳一下：记下是哪种反应、从什么时候开始。
      let pokeAt = -Infinity
      let pokeKind: (typeof POKES)[number]['kind'] = 'shake'
      let pokeIndex = 0
      const poke = () => {
        const now = performance.now() / 1000
        if (now - pokeAt < POKE_COOLDOWN) return
        pokeKind = POKES[pokeIndex % POKES.length].kind
        pokeIndex += 1
        pokeAt = now
      }
      if (pokeRefRef.current) pokeRefRef.current.current = poke
      let tilt = 0
      const internal = live.internalModel as unknown as { physics?: { evaluate(model: unknown, seconds: number): void } }
      const physics = internal.physics
      internal.physics = undefined
      live.internalModel.on('beforeModelUpdate', () => {
        const now = performance.now()
        const delta = Math.min(0.05, (now - last) / 1000)
        last = now
        const current = moodRef.current
        // 开始说一段话时，偶尔做个小动作。模型没有这组动作时什么都不发生。
        if (current === 'speaking' && previousMood !== 'speaking' && !calm && Math.random() < 0.35) {
          void live.motion('TapBody')
        }
        previousMood = current

        const raw = Math.max(0, Math.min(1, levelRef.current?.() ?? 0))
        smooth += (raw - smooth) * (raw > smooth ? 0.5 : 0.2)
        let mouth = current === 'speaking' ? Math.min(1, smooth * 1.6) : 0

        // 戳一下的反应，t 是开始后过了几秒。
        const t = now / 1000 - pokeAt
        const reacting = POKES.find((item) => item.kind === pokeKind)!
        const active = t >= 0 && t < reacting.seconds
        let squint = 1
        let offsetY = 0
        const extra = { angleX: 0, angleY: 0, angleZ: 0 }
        if (active && pokeKind === 'shake') {
          extra.angleX = calm ? 0 : 20 * Math.sin(t * Math.PI * 6) * Math.exp(-3.5 * t)
          squint = t < 0.35 ? 0.35 : 1
        } else if (active && pokeKind === 'hop') {
          const k = t / reacting.seconds
          offsetY = calm ? 0 : -hopHeight * Math.sin(Math.PI * k)
          extra.angleY = calm ? 0 : 10 * Math.sin(Math.PI * k)
        } else if (active && pokeKind === 'startle') {
          extra.angleY = (calm ? 6 : 16) * Math.exp(-2.5 * t) * Math.min(1, t / 0.08)
          mouth = Math.max(mouth, t < 0.55 ? 0.7 : 0)
          squint = (t > 0.35 && t < 0.45) || (t > 0.55 && t < 0.65) ? 0 : 1
        }
        live.position.y = baseY + offsetY
        for (const id of lipIds) core.setParameterValueById(id, mouth)
        if (squint < 1) {
          for (const id of ['ParamEyeLOpen', 'ParamEyeROpen']) core.setParameterValueById(id, Math.min(core.getParameterValueById(id), squint))
        }

        if (!calm) {
          const wanted = POSE[current]
          const ease = 1 - Math.exp(-delta * 5)
          for (const key of Object.keys(pose) as Array<keyof typeof pose>) pose[key] += (wanted[key] - pose[key]) * ease
          const nod = current === 'speaking' ? Math.sin(now / 240) * 2.5 * smooth : 0
          core.addParameterValueById('ParamAngleX', pose.angleX)
          core.addParameterValueById('ParamAngleY', pose.angleY + nod)
          core.addParameterValueById('ParamAngleZ', pose.angleZ)
          core.addParameterValueById('ParamBodyAngleX', pose.angleX * 0.3)
          core.addParameterValueById('ParamEyeBallX', pose.eyeX)
          core.addParameterValueById('ParamEyeBallY', pose.eyeY)
          // 跟着指针看：眼睛转得多，头转得少。悬停时歪着头看你。
          const follow = 1 - Math.exp(-delta * 6)
          look.x += (aim.x - look.x) * follow; look.y += (aim.y - look.y) * follow
          tilt += ((emphasisRef.current ? 1 : 0) - tilt) * (1 - Math.exp(-delta * 4))
          core.addParameterValueById('ParamEyeBallX', look.x * 0.8)
          core.addParameterValueById('ParamEyeBallY', -look.y * 0.6)
          core.addParameterValueById('ParamAngleX', look.x * 10 + extra.angleX)
          core.addParameterValueById('ParamAngleY', -look.y * 8 + extra.angleY + tilt * 4)
          core.addParameterValueById('ParamAngleZ', tilt * 10 + extra.angleZ)
          core.addParameterValueById('ParamBodyAngleX', look.x * 3)
        } else {
          core.addParameterValueById('ParamAngleY', extra.angleY)
        }
        physics?.evaluate(core, delta)
      })
      setState('ready')

      cleanup = () => {
        window.removeEventListener('pointermove', onPointer)
        if (pokeRefRef.current?.current === poke) pokeRefRef.current.current = null
        observer?.disconnect()
        live.destroy()
        app.destroy(true, { children: true })
      }
    })().catch(() => { if (!disposed) setState('failed') })

    return () => { disposed = true; cleanup() }
  }, [model.url])

  return <div className={`character live2d mood-${mood} ${state}`} aria-hidden="true">
    <div ref={mountRef} className="character-mount" />
    {state === 'loading' && <span className="live2d-loading"><i /></span>}
    {state === 'failed' && (look.body
      // eslint-disable-next-line @next/next/no-img-element
      ? <img className="character-still" src={look.body} alt="" draggable={false} />
      : <span className="character-initial">{name.trim().slice(0, 1) || '·'}</span>)}
  </div>
}
