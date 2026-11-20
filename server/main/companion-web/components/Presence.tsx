'use client'

import { useEffect, useRef, useState } from 'react'
import { roleLook } from '../lib/roles'

/** 角色现在在做什么。决定光球流动的快慢和玩偶的姿态。 */
export type PresenceMood = 'offline' | 'idle' | 'hearing' | 'thinking' | 'speaking'

interface PresenceProps {
  name: string
  mood: PresenceMood
  /** 每一帧调用一次，返回 0 到 1 的音量：用户在说时是麦克风的音量，角色在说时是它自己声音的音量。 */
  level?: () => number
}

const VERTEX = 'attribute vec2 p; void main() { gl_Position = vec4(p, 0., 1.); }'

// 一个会流动的光球：球心是暗的，三种颜色在边缘缓慢流动，边缘随音量起伏，外面是一圈光晕。
const FRAGMENT = `
precision mediump float;
uniform vec2 u_res; uniform float u_time; uniform float u_level; uniform float u_dim;
uniform vec3 u_c1; uniform vec3 u_c2; uniform vec3 u_c3;
vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 permute(vec4 x) { return mod289(((x * 34.0) + 1.0) * x); }
vec4 taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }
float snoise(vec3 v) {
  const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0); const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
  vec3 i = floor(v + dot(v, C.yyy)); vec3 x0 = v - i + dot(i, C.xxx);
  vec3 g = step(x0.yzx, x0.xyz); vec3 l = 1.0 - g; vec3 i1 = min(g.xyz, l.zxy); vec3 i2 = max(g.xyz, l.zxy);
  vec3 x1 = x0 - i1 + C.xxx; vec3 x2 = x0 - i2 + C.yyy; vec3 x3 = x0 - D.yyy;
  i = mod289(i);
  vec4 p = permute(permute(permute(i.z + vec4(0.0, i1.z, i2.z, 1.0)) + i.y + vec4(0.0, i1.y, i2.y, 1.0)) + i.x + vec4(0.0, i1.x, i2.x, 1.0));
  float n_ = 0.142857142857; vec3 ns = n_ * D.wyz - D.xzx;
  vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
  vec4 x_ = floor(j * ns.z); vec4 y_ = floor(j - 7.0 * x_);
  vec4 x = x_ * ns.x + ns.yyyy; vec4 y = y_ * ns.x + ns.yyyy; vec4 h = 1.0 - abs(x) - abs(y);
  vec4 b0 = vec4(x.xy, y.xy); vec4 b1 = vec4(x.zw, y.zw);
  vec4 s0 = floor(b0) * 2.0 + 1.0; vec4 s1 = floor(b1) * 2.0 + 1.0; vec4 sh = -step(h, vec4(0.0));
  vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy; vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
  vec3 p0 = vec3(a0.xy, h.x); vec3 p1 = vec3(a0.zw, h.y); vec3 p2 = vec3(a1.xy, h.z); vec3 p3 = vec3(a1.zw, h.w);
  vec4 norm = taylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
  p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
  vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0); m = m * m;
  return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}
void main() {
  vec2 uv = (gl_FragCoord.xy - 0.5 * u_res) / min(u_res.x, u_res.y);
  float t = u_time;
  float angle = atan(uv.y, uv.x);
  // 安静时是一个正圆，有声音时边缘才起伏。
  float radius = 0.33 + 0.025 * u_level;
  float wobble = snoise(vec3(cos(angle) * 1.3, sin(angle) * 1.3, t * 0.6)) * (0.002 + 0.024 * u_level);
  float d = length(uv) - (radius + wobble);
  vec2 q = uv / (radius + wobble);
  float z = sqrt(max(0.0, 1.0 - dot(q, q)));
  float n1 = snoise(vec3(q * 1.2 + vec2(0.0, t * 0.12), t * 0.15));
  float n2 = snoise(vec3(q * 2.0 - vec2(t * 0.1, 0.0) + n1 * 0.6, t * 0.2 + 4.0));
  vec3 flow = mix(u_c2, u_c1, smoothstep(-0.5, 0.6, n1));
  flow = mix(flow, u_c3, smoothstep(0.0, 0.9, n2) * 0.8);
  // 球心是暗的，颜色集中在边缘流动，这样坐在前面的角色看得清楚；有声音时球心也跟着亮起来。
  float rim = pow(1.0 - z, 1.5);
  vec3 core = mix(vec3(0.035, 0.03, 0.06), u_c2 * 0.22, 0.6);
  vec3 col = core + flow * (0.1 + 1.05 * rim) * (0.85 + 0.6 * u_level) + flow * u_level * 0.22;
  col += vec3(0.5) * pow(1.0 - z, 6.0);
  float body = smoothstep(0.006, -0.006, d);
  float glow = exp(-max(d, 0.0) * (9.0 - 4.0 * u_level)) * (0.38 + 0.5 * u_level);
  // 光晕在画布边缘之前就要淡到没有，否则会看到画布的方形边界。
  glow *= smoothstep(0.5, 0.37, length(uv));
  vec3 halo = mix(mix(u_c2, u_c3, 0.5 + 0.5 * sin(angle + t * 0.4)), u_c1, 0.25);
  vec3 rgb = col * body + halo * glow * (1.0 - body);
  float alpha = body + glow * (1.0 - body);
  gl_FragColor = vec4(rgb, alpha) * u_dim;
}`

function rgb(hex: string): [number, number, number] {
  const value = parseInt(hex.slice(1), 16)
  return [(value >> 16 & 255) / 255, (value >> 8 & 255) / 255, (value & 255) / 255]
}

/** 每种状态下光球流动的速度，以及没有声音时的基础起伏。 */
const MOTION: Record<PresenceMood, { speed: number; rest: number; dim: number }> = {
  offline: { speed: 0.15, rest: 0, dim: 0.45 },
  idle: { speed: 0.45, rest: 0.04, dim: 1 },
  hearing: { speed: 0.8, rest: 0.12, dim: 1 },
  thinking: { speed: 1.5, rest: 0.1, dim: 1 },
  speaking: { speed: 1, rest: 0.1, dim: 1 },
}

/**
 * 舞台上的角色：一个随声音流动的光球，前面坐着角色的玩偶（如果这个角色有）。
 *
 * 用户说话时它跟着麦克风的音量动，它自己说话时跟着自己声音的音量动，想的时候光球转得快一些。
 * 浏览器不支持 WebGL 时退回到样式画的光球；用户在系统里选了“减少动态效果”时只保留很慢的流动。
 */
export default function Presence({ name, mood, level }: PresenceProps) {
  const look = roleLook(name)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const moodRef = useRef(mood)
  moodRef.current = mood
  const levelRef = useRef(level)
  levelRef.current = level
  const auraRef = useRef(look.aura)
  auraRef.current = look.aura
  const [flat, setFlat] = useState(false)
  const [bodyFailed, setBodyFailed] = useState(false)
  useEffect(() => { setBodyFailed(false) }, [look.body])
  const hasBody = Boolean(look.body) && !bodyFailed

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    let gl: WebGLRenderingContext | null = null
    // 没有 WebGL 的环境（很老的浏览器、测试环境）直接用样式画的光球。
    if (typeof WebGLRenderingContext !== 'undefined') {
      try { gl = canvas.getContext('webgl', { premultipliedAlpha: true, antialias: false }) } catch { gl = null }
    }
    const calm = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches

    let program: WebGLProgram | null = null
    const uniforms: Record<string, WebGLUniformLocation | null> = {}
    if (gl) {
      const compile = (type: number, source: string) => {
        const shader = gl!.createShader(type)
        if (!shader) return null
        gl!.shaderSource(shader, source); gl!.compileShader(shader)
        return gl!.getShaderParameter(shader, gl!.COMPILE_STATUS) ? shader : null
      }
      const vertex = compile(gl.VERTEX_SHADER, VERTEX)
      const fragment = compile(gl.FRAGMENT_SHADER, FRAGMENT)
      program = vertex && fragment ? gl.createProgram() : null
      if (program && vertex && fragment) {
        gl.attachShader(program, vertex); gl.attachShader(program, fragment); gl.linkProgram(program)
        if (!gl.getProgramParameter(program, gl.LINK_STATUS)) program = null
      }
      if (program) {
        gl.useProgram(program)
        const buffer = gl.createBuffer()
        gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
        const position = gl.getAttribLocation(program, 'p')
        gl.enableVertexAttribArray(position)
        gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0)
        for (const key of ['u_res', 'u_time', 'u_level', 'u_dim', 'u_c1', 'u_c2', 'u_c3']) uniforms[key] = gl.getUniformLocation(program, key)
        gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
      }
    }
    setFlat(!gl || !program)
    // 既画不了光球、也没有玩偶要动，就不需要逐帧更新。
    if ((!gl || !program) && !hasBody) return

    let frame = 0
    let last = performance.now()
    let clock = 0          // 光球自己的时间，按状态快慢推进
    let smooth = 0         // 平滑后的音量：起得快，落得慢
    let tilt = 0
    let dim = MOTION[moodRef.current].dim
    const draw = (now: number) => {
      frame = requestAnimationFrame(draw)
      const delta = Math.min(0.05, (now - last) / 1000)
      last = now
      const motion = MOTION[moodRef.current]
      clock += delta * (calm ? 0.2 : motion.speed)
      const raw = Math.max(0, Math.min(1, levelRef.current?.() ?? 0))
      const target = calm ? motion.rest : Math.max(raw, motion.rest * (0.6 + 0.4 * Math.sin(now / 520)))
      smooth += (target - smooth) * (target > smooth ? 0.35 : 0.08)
      dim += (motion.dim - dim) * 0.06

      if (gl && program) {
        const size = Math.round(Math.min(canvas.clientWidth * Math.min(window.devicePixelRatio || 1, 2), 900))
        if (size > 0 && canvas.width !== size) { canvas.width = size; canvas.height = size }
        gl.viewport(0, 0, canvas.width, canvas.height)
        gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT)
        gl.uniform2f(uniforms.u_res, canvas.width, canvas.height)
        gl.uniform1f(uniforms.u_time, clock)
        gl.uniform1f(uniforms.u_level, smooth)
        gl.uniform1f(uniforms.u_dim, dim)
        const [c1, c2, c3] = auraRef.current
        gl.uniform3f(uniforms.u_c1, ...rgb(c1)); gl.uniform3f(uniforms.u_c2, ...rgb(c2)); gl.uniform3f(uniforms.u_c3, ...rgb(c3))
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
      }

      // 玩偶的姿态：平时轻轻呼吸；听的时候歪一点头凑近；想的时候慢慢左右晃；说的时候跟着声音一颠一颠。
      const body = bodyRef.current
      if (body && !calm) {
        const current = moodRef.current
        const wanted = current === 'hearing' ? 4 : current === 'thinking' ? Math.sin(now / 900) * 3.5 : current === 'speaking' ? Math.sin(now / 260) * 1.2 : 0
        tilt += (wanted - tilt) * 0.08
        const breath = Math.sin(now / 1500) * 0.008
        const bounce = current === 'speaking' ? smooth : current === 'hearing' ? smooth * 0.35 : 0
        const lift = bounce * 12 + (current === 'hearing' ? 4 : 0)
        body.style.transform = `translateY(${(-lift).toFixed(2)}px) rotate(${tilt.toFixed(2)}deg) scale(${(1 - bounce * 0.025).toFixed(4)}, ${(1 + breath + bounce * 0.05).toFixed(4)})`
      }
    }
    frame = requestAnimationFrame(draw)
    return () => { cancelAnimationFrame(frame) }
  }, [hasBody])

  const [c1, c2, c3] = look.aura
  return <div
    className={`presence-figure mood-${mood} ${flat ? 'flat' : ''}`} aria-hidden="true"
    style={{ '--aura-1': c1, '--aura-2': c2, '--aura-3': c3 } as React.CSSProperties}
  >
    <canvas ref={canvasRef} className="presence-orb" />
    <div className="presence-orb-flat" />
    {hasBody && <div ref={bodyRef} className="presence-body">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={look.body} alt="" draggable={false} onError={() => setBodyFailed(true)} />
    </div>}
  </div>
}
