'use client'

import { useEffect, useRef } from 'react'
import { Mic, MicOff, X } from 'lucide-react'

interface LiveBarProps {
  /** 现在的状态，比如“在听你说…”。 */
  label: string
  /** 每一帧调用一次，返回 0 到 1 的音量。 */
  level: () => number
  /** 角色的三种颜色，声浪用它们。 */
  aura: [string, string, string]
  muted: boolean
  onToggleMute: () => void
  onEnd: () => void
}

/**
 * 语音通话时取代输入栏的一条：中间是一颗随声音涨落的光，左边关麦克风，右边结束通话。
 * 上面一行小字写着现在的状态。
 */
export default function LiveBar({ label, level, aura, muted, onToggleMute, onEnd }: LiveBarProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const levelRef = useRef(level)
  levelRef.current = level
  const auraRef = useRef(aura)
  auraRef.current = aura

  useEffect(() => {
    const canvas = canvasRef.current
    // 测试环境没有画布，声浪只是装饰，画不了就不画。
    if (!canvas || typeof CanvasRenderingContext2D === 'undefined') return
    const context = canvas.getContext('2d')
    if (!context) return
    const calm = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
    let frame = 0
    let smooth = 0
    const draw = (now: number) => {
      frame = requestAnimationFrame(draw)
      // 画得很小，再由样式放大并模糊，得到的是一团柔和的光而不是几条硬边的波浪。
      const width = Math.round(canvas.clientWidth / 2); const height = Math.round(canvas.clientHeight / 2)
      if (!width || !height) return
      if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height }
      const raw = Math.max(0, Math.min(1, levelRef.current()))
      smooth += (raw - smooth) * (raw > smooth ? 0.35 : 0.08)
      const t = calm ? 0 : now / 1000
      context.clearRect(0, 0, width, height)
      const [c1, c2, c3] = auraRef.current
      const layers = [
        { color: c3, alpha: 0.75, speed: 0.7, phase: 1.3, lift: -0.06 },
        { color: c1, alpha: 0.8, speed: 1.0, phase: 3.4, lift: 0.02 },
        { color: c2, alpha: 1, speed: 1.35, phase: 5.1, lift: 0.12 },
      ]
      for (const layer of layers) {
        // 安静时光只在底部三分之一，声音越大涨得越高、起伏越大。
        const base = height * (0.66 + layer.lift - smooth * 0.5)
        const swell = height * (0.06 + smooth * 0.2)
        context.globalAlpha = layer.alpha
        context.fillStyle = layer.color
        context.beginPath()
        context.moveTo(0, height)
        for (let x = 0; x <= width; x += 3) {
          const u = x / width
          const y = base + Math.sin(u * 4.2 + t * layer.speed * 1.8 + layer.phase) * swell * 0.7 + Math.sin(u * 7.6 - t * layer.speed * 1.2 + layer.phase) * swell * 0.3
          context.lineTo(x, y)
        }
        context.lineTo(width, height)
        context.closePath()
        context.fill()
      }
      context.globalAlpha = 1
    }
    frame = requestAnimationFrame(draw)
    return () => cancelAnimationFrame(frame)
  }, [])

  return <div className="live" role="group" aria-label="语音通话">
    <p className="live-state" key={muted ? 'muted' : label}>{muted ? '麦克风已关' : label}</p>
    <div className="live-bar">
      <button
        type="button" className={`glass-button ${muted ? 'off' : ''}`} onClick={onToggleMute} aria-pressed={muted}
        aria-label={muted ? '打开麦克风' : '关闭麦克风'}
      >{muted ? <MicOff size={23} /> : <Mic size={23} />}</button>
      <div className={`wave-pill ${muted ? 'muted' : ''}`}><canvas ref={canvasRef} aria-hidden="true" /></div>
      <button type="button" className="glass-button" onClick={onEnd} aria-label="结束实时通话"><X size={25} /></button>
    </div>
  </div>
}
