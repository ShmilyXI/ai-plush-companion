'use client'

import { useEffect, useRef, useState } from 'react'
import { roleLook, type PlushSpec } from '../lib/roles'

/** 角色现在在做什么。决定它的姿态。 */
export type CharacterMood = 'offline' | 'idle' | 'hearing' | 'thinking' | 'speaking'

interface CharacterProps {
  name: string
  mood: CharacterMood
  /** 每一帧调用一次，返回 0 到 1 的音量：用户在说时是麦克风的音量，角色在说时是它自己声音的音量。 */
  level?: () => number
}

type Three = typeof import('three')

/** 没有专门形象的角色用的样子：一只小熊，颜色取角色自己的光。 */
function fallbackSpec(aura: [string, string, string]): PlushSpec {
  return { kind: 'bear', fur: aura[0], inner: aura[1], accent: '#ffffff', nose: '#3a3340' }
}

/** 一小块随机噪点，贴在表面上做出毛毡那种细小的起伏。 */
function feltTexture(THREE: Three) {
  const size = 128
  const canvas = document.createElement('canvas')
  canvas.width = size; canvas.height = size
  const context = canvas.getContext('2d')
  if (!context) return null
  const image = context.createImageData(size, size)
  for (let index = 0; index < image.data.length; index += 4) {
    const value = 110 + Math.random() * 90
    image.data[index] = value; image.data[index + 1] = value; image.data[index + 2] = value; image.data[index + 3] = 255
  }
  context.putImageData(image, 0, 0)
  const texture = new THREE.CanvasTexture(canvas)
  texture.wrapS = THREE.RepeatWrapping; texture.wrapT = THREE.RepeatWrapping
  texture.repeat.set(5, 5)
  return texture
}

/**
 * 用几个圆润的形状搭出一只玩偶：头、身体、手、脚、耳朵、眼睛、嘴。
 * 返回的各个部件在每一帧里被转动、缩放，做出眨眼、张嘴、歪头、晃耳朵。
 */
function buildPlush(THREE: Three, spec: PlushSpec) {
  const ball = new THREE.SphereGeometry(1, 48, 32)
  const bump = feltTexture(THREE)
  const cloth = (color: string) => new THREE.MeshPhysicalMaterial({
    color, roughness: 0.96, metalness: 0,
    sheen: 1, sheenRoughness: 0.55, sheenColor: new THREE.Color(color).lerp(new THREE.Color('#ffffff'), 0.3),
    ...(bump ? { bumpMap: bump, bumpScale: 0.9 } : {}),
  })
  const fur = cloth(spec.fur)
  const inner = cloth(spec.inner)
  const accent = cloth(spec.accent)
  const dark = new THREE.MeshPhysicalMaterial({ color: '#17141c', roughness: 0.18, clearcoat: 1, clearcoatRoughness: 0.1 })
  const noseMaterial = new THREE.MeshPhysicalMaterial({ color: spec.nose, roughness: 0.5 })
  const part = (material: InstanceType<Three['Material']>, sx: number, sy: number, sz: number, x = 0, y = 0, z = 0) => {
    const mesh = new THREE.Mesh(ball, material)
    mesh.scale.set(sx, sy, sz); mesh.position.set(x, y, z)
    return mesh
  }

  const root = new THREE.Group()
  const body = part(fur, 0.5, 0.54, 0.45, 0, -0.62, 0)
  root.add(body)
  if (spec.kind === 'bear') root.add(part(accent, 0.3, 0.34, 0.2, 0, -0.64, 0.29))
  root.add(part(fur, 0.2, 0.17, 0.27, -0.27, -1.04, 0.3), part(fur, 0.2, 0.17, 0.27, 0.27, -1.04, 0.3))

  const arms = [-1, 1].map((side) => {
    const pivot = new THREE.Group()
    pivot.position.set(side * 0.42, -0.36, 0.06)
    pivot.rotation.z = side * 0.42
    pivot.add(part(fur, 0.15, 0.29, 0.15, 0, -0.24, 0))
    root.add(pivot)
    return pivot
  })

  // 头是一个略扁的球。head 里的东西都跟着头一起转。
  const head = new THREE.Group()
  head.position.set(0, 0.3, 0)
  const R = { x: 0.68, y: 0.59, z: 0.6 }
  head.add(part(fur, R.x, R.y, R.z))
  /** 头表面上的一点：给出左右和上下的位置，算出它在前表面上有多靠前。 */
  const front = (x: number, y: number) => R.z * Math.sqrt(Math.max(0, 1 - (x / R.x) ** 2 - (y / R.y) ** 2))

  const eyes = [-1, 1].map((side) => {
    const x = side * 0.23; const y = 0.05
    const eye = new THREE.Group()
    eye.position.set(x, y, front(x, y) - 0.012)
    eye.add(part(dark, 0.062, 0.068, 0.045))
    eye.add(part(new THREE.MeshBasicMaterial({ color: '#ffffff' }), 0.016, 0.016, 0.01, -0.018, 0.024, 0.04))
    head.add(eye)
    return eye
  })

  const faceY = spec.kind === 'bear' ? -0.1 : -0.09
  if (spec.kind === 'bear') {
    head.add(part(accent, 0.21, 0.16, 0.13, 0, faceY, front(0, faceY) - 0.03))
    head.add(part(noseMaterial, 0.06, 0.042, 0.035, 0, faceY + 0.07, front(0, faceY) + 0.085))
    for (const side of [-1, 1]) head.add(part(new THREE.MeshPhysicalMaterial({ color: '#ff9aa6', roughness: 1, transparent: true, opacity: 0.85 }), 0.085, 0.06, 0.02, side * 0.38, -0.1, front(side * 0.38, -0.1) - 0.008))
  } else if (spec.kind === 'cat') {
    head.add(part(noseMaterial, 0.034, 0.024, 0.02, 0, faceY + 0.035, front(0, faceY + 0.035)))
  }
  // 嘴：闭着时是一道短线，说话时按音量张开。
  const mouthY = spec.kind === 'bear' ? faceY - 0.035 : faceY - 0.035
  const mouthZ = spec.kind === 'bear' ? front(0, faceY) + 0.09 : front(0, mouthY) + 0.002
  const mouth = part(new THREE.MeshBasicMaterial({ color: '#5b2b33' }), 0.05, 0.008, 0.02, 0, mouthY, mouthZ)
  head.add(mouth)

  const ears = [-1, 1].map((side) => {
    const pivot = new THREE.Group()
    if (spec.kind === 'rabbit') {
      pivot.position.set(side * 0.47, 0.36, -0.04)
      pivot.rotation.z = side * 0.2
      pivot.add(part(fur, 0.17, 0.5, 0.1, side * 0.1, -0.42, 0))
      pivot.add(part(inner, 0.1, 0.38, 0.05, side * 0.1, -0.44, 0.066))
    } else if (spec.kind === 'cat') {
      pivot.position.set(side * 0.38, 0.4, 0)
      pivot.rotation.z = -side * 0.32
      const cone = new THREE.Mesh(new THREE.ConeGeometry(0.2, 0.36, 28), fur)
      cone.position.y = 0.13
      const pink = new THREE.Mesh(new THREE.ConeGeometry(0.11, 0.22, 20), inner)
      pink.position.set(0, 0.1, 0.085)
      pivot.add(cone, pink)
    } else {
      pivot.position.set(side * 0.5, 0.44, 0)
      pivot.add(part(fur, 0.2, 0.2, 0.12))
      pivot.add(part(inner, 0.115, 0.115, 0.05, 0, 0, 0.09))
    }
    head.add(pivot)
    return pivot
  })
  root.add(head)

  // 脚下的一团影子。它不跟着角色颠，所以单独返回，直接放进场景。
  const shade = document.createElement('canvas')
  shade.width = 128; shade.height = 128
  const pen = shade.getContext('2d')
  if (pen) {
    const gradient = pen.createRadialGradient(64, 64, 4, 64, 64, 62)
    gradient.addColorStop(0, 'rgba(30, 28, 50, 0.34)'); gradient.addColorStop(1, 'rgba(30, 28, 50, 0)')
    pen.fillStyle = gradient; pen.fillRect(0, 0, 128, 128)
  }
  const shadow = new THREE.Mesh(
    new THREE.PlaneGeometry(1.9, 1.9),
    new THREE.MeshBasicMaterial({ map: new THREE.CanvasTexture(shade), transparent: true, depthWrite: false }),
  )
  shadow.rotation.x = -Math.PI / 2
  shadow.position.set(0, -1.21, 0.1)
  return { root, head, body, arms, eyes, ears, mouth, shadow }
}

/**
 * 实时渲染的 3D 角色。它会眨眼；听的时候歪头凑近；想的时候往上看、慢慢晃；
 * 说话时嘴按自己声音的音量开合，身体跟着一颠一颠，耳朵跟着晃。
 *
 * 3D 库只在浏览器能用 WebGL 时才加载。不能用时（很老的浏览器、测试环境）显示角色的静态图片。
 */
export default function Character({ name, mood, level }: CharacterProps) {
  const look = roleLook(name)
  const mountRef = useRef<HTMLDivElement>(null)
  const moodRef = useRef(mood)
  moodRef.current = mood
  const levelRef = useRef(level)
  levelRef.current = level
  const [ready, setReady] = useState(false)
  const specKey = JSON.stringify(look.plush ?? fallbackSpec(look.aura))

  useEffect(() => {
    const mount = mountRef.current
    if (!mount || typeof WebGLRenderingContext === 'undefined') return
    let disposed = false
    let cleanup = () => undefined as void
    setReady(false)
    void import('three').then((THREE) => {
      if (disposed) return
      let renderer: InstanceType<Three['WebGLRenderer']>
      try { renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' }) } catch { return }
      renderer.outputColorSpace = THREE.SRGBColorSpace
      renderer.toneMapping = THREE.ACESFilmicToneMapping
      renderer.toneMappingExposure = 0.98
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
      renderer.domElement.className = 'character-canvas'
      mount.appendChild(renderer.domElement)

      const scene = new THREE.Scene()
      const camera = new THREE.PerspectiveCamera(27, 1, 0.1, 30)
      camera.position.set(0, 0.05, 5.6)
      camera.lookAt(0, -0.2, 0)
      scene.add(new THREE.HemisphereLight('#ffffff', '#b9b2c9', 1.25))
      const key = new THREE.DirectionalLight('#fff6ea', 2.3)
      key.position.set(-2.2, 3, 4)
      const fill = new THREE.DirectionalLight('#e8ecff', 0.7)
      fill.position.set(3, 0.5, 2)
      const rim = new THREE.DirectionalLight(look.aura[1], 1.6)
      rim.position.set(1.5, 2, -4)
      scene.add(key, fill, rim)

      const plush = buildPlush(THREE, JSON.parse(specKey) as PlushSpec)
      scene.add(plush.root, plush.shadow)

      const resize = () => {
        const width = mount.clientWidth; const height = mount.clientHeight
        if (!width || !height) return
        renderer.setSize(width, height, false)
        camera.aspect = width / height
        // 窄而高的区域里角色按宽度放，宽的区域里按高度放，始终完整。
        camera.position.z = camera.aspect < 0.8 ? 5.6 * (0.8 / camera.aspect) : 5.6
        camera.updateProjectionMatrix()
      }
      const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(resize) : null
      observer?.observe(mount)
      resize()

      const calm = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
      let frame = 0
      let last = performance.now()
      let smooth = 0
      let nextBlink = last + 1800 + Math.random() * 2500
      let blinkUntil = 0
      // 耳朵用弹簧跟在头后面：头一动、声音一响，耳朵晚一点才跟上，再晃回来。
      const ear = { angle: 0, speed: 0 }
      const pose = { yaw: 0, pitch: 0, roll: 0, lean: 0 }
      const draw = (now: number) => {
        frame = requestAnimationFrame(draw)
        const delta = Math.min(0.05, (now - last) / 1000)
        last = now
        const current = moodRef.current
        const raw = Math.max(0, Math.min(1, levelRef.current?.() ?? 0))
        smooth += (raw - smooth) * (raw > smooth ? 0.45 : 0.14)
        const t = now / 1000

        const wanted = calm ? { yaw: 0, pitch: 0, roll: 0, lean: 0 }
          : current === 'hearing' ? { yaw: 0.06, pitch: 0.08, roll: 0.16, lean: 0.1 }
            : current === 'thinking' ? { yaw: 0.28 + Math.sin(t * 0.9) * 0.08, pitch: -0.2, roll: Math.sin(t * 0.7) * 0.07, lean: 0 }
              : current === 'speaking' ? { yaw: Math.sin(t * 1.3) * 0.1, pitch: 0.04 + smooth * 0.1, roll: Math.sin(t * 1.9) * 0.05, lean: 0.03 }
                : current === 'offline' ? { yaw: 0, pitch: 0.22, roll: 0, lean: 0 }
                  : { yaw: Math.sin(t * 0.45) * 0.12, pitch: Math.sin(t * 0.6) * 0.04, roll: Math.sin(t * 0.37) * 0.04, lean: 0 }
        const ease = 1 - Math.exp(-delta * 6)
        const before = pose.yaw + pose.roll
        pose.yaw += (wanted.yaw - pose.yaw) * ease
        pose.pitch += (wanted.pitch - pose.pitch) * ease
        pose.roll += (wanted.roll - pose.roll) * ease
        pose.lean += (wanted.lean - pose.lean) * ease
        plush.head.rotation.set(pose.pitch, pose.yaw, pose.roll)

        const voice = current === 'speaking' ? smooth : 0
        const breath = calm ? 0 : Math.sin(t * 1.9) * 0.012
        plush.body.scale.set(0.5 - breath * 0.4, 0.54 + breath, 0.45 + breath * 0.5)
        plush.root.position.y = voice * 0.07 + (current === 'hearing' ? 0.02 : 0)
        // 角色颠起来时影子变小变淡。
        plush.shadow.scale.setScalar(1 - voice * 0.18)
        plush.root.rotation.x = pose.lean
        plush.root.rotation.z = calm ? 0 : Math.sin(t * 0.8) * 0.012
        plush.head.position.y = 0.3 + breath * 0.8 + voice * 0.03

        // 嘴按声音开合。
        plush.mouth.scale.set(0.05 - voice * 0.012, 0.008 + voice * 0.07, 0.02)

        // 眨眼。说话和思考时也照常眨。
        if (now > nextBlink) { blinkUntil = now + 130; nextBlink = now + 2200 + Math.random() * 3200 }
        const lid = now < blinkUntil || current === 'offline' ? 0.12 : 1
        for (const eye of plush.eyes) eye.scale.y += (lid - eye.scale.y) * 0.5

        // 耳朵。
        const push = ((pose.yaw + pose.roll) - before) * 22 + (current === 'speaking' ? Math.sin(t * 9) * smooth * 0.6 : 0)
        ear.speed += (-ear.angle * 60 - ear.speed * 7 - push * 40) * delta
        ear.angle += ear.speed * delta
        const swing = calm ? 0 : Math.max(-0.5, Math.min(0.5, ear.angle))
        plush.ears.forEach((pivot, index) => {
          const side = index === 0 ? -1 : 1
          const rest = pivot.userData.rest ?? (pivot.userData.rest = pivot.rotation.z)
          pivot.rotation.z = rest + swing * (0.6 + 0.4 * side * Math.sign(swing || 1))
          pivot.rotation.x = current === 'hearing' ? -0.12 : 0
        })

        // 手：说话时轻轻抬起来比划。
        plush.arms.forEach((pivot, index) => {
          const side = index === 0 ? -1 : 1
          pivot.rotation.z = side * (0.42 + voice * 0.35 + (calm ? 0 : Math.sin(t * 1.9 + index) * 0.02))
          pivot.rotation.x = -voice * 0.5
        })
        renderer.render(scene, camera)
      }
      frame = requestAnimationFrame(draw)
      setReady(true)

      cleanup = () => {
        cancelAnimationFrame(frame)
        observer?.disconnect()
        scene.traverse((object) => {
          const mesh = object as { geometry?: { dispose(): void }; material?: { dispose(): void; map?: { dispose(): void } | null } }
          mesh.geometry?.dispose(); mesh.material?.map?.dispose(); mesh.material?.dispose()
        })
        renderer.dispose()
        renderer.domElement.remove()
      }
    }).catch(() => undefined)
    return () => { disposed = true; cleanup() }
    // 角色的光只影响轮廓光的颜色，跟着形象一起重建就够了。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [specKey])

  return <div className={`character mood-${mood}`} aria-hidden="true">
    <div ref={mountRef} className="character-mount" />
    {!ready && (look.body
      // eslint-disable-next-line @next/next/no-img-element
      ? <img className="character-still" src={look.body} alt="" draggable={false} />
      : <span className="character-initial">{name.trim().slice(0, 1) || '·'}</span>)}
  </div>
}
