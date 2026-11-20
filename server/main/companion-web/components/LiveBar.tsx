'use client'

import { useEffect, useRef } from 'react'
import { Mic, MicOff, Smile, X } from 'lucide-react'

interface LiveBarProps {
  /** 现在的状态，比如“在听你说…”。 */
  label: string
  /** 每一帧调用一次，返回 0 到 1 的音量。 */
  level: () => number
  /** 角色的三种颜色，声浪用它们。 */
  aura: [string, string, string]
  muted: boolean
  onToggleMute: () => void
  figureOpen: boolean
  onToggleFigure: () => void
  onEnd: () => void
}

/**
 * 语音通话时取代输入栏的一条：中间是随声音起伏的声浪，左边是显示或收起角色，右边是关麦克风和结束通话。
 */
export default function LiveBar({ label, level, aura, muted, onToggleMute, figureOpen, onToggleFigure, onEnd }: LiveBarProps) {
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
      const ratio = Math.min(window.devicePixelRatio || 1, 2)
      const width = Math.round(canvas.clientWidth * ratio); const height = Math.round(canvas.clientHeight * ratio)
      if (!width || !height) return
      if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height }
      const raw = Math.max(0, Math.min(1, levelRef.current()))
      smooth += (raw - smooth) * (raw > smooth ? 0.4 : 0.1)
      const t = calm ? 0 : now / 1000
      context.clearRect(0, 0, width, height)
      const [c1, c2, c3] = auraRef.current
      // 三层波浪叠在一起，从下往上涨；声音越大涨得越高、起伏越大。
      const layers = [
        { color: c1, alpha: 0.55, speed: 0.9, phase: 0, lift: 0.0 },
        { color: c3, alpha: 0.5, speed: 1.3, phase: 2.1, lift: 0.06 },
        { color: c2, alpha: 0.95, speed: 1.7, phase: 4.2, lift: 0.14 },
      ]
      for (const layer of layers) {
        const base = height * (0.72 + layer.lift - smooth * 0.42)
        const swell = height * (0.05 + smooth * 0.2)
        const fill = context.createLinearGradient(0, base - swell, 0, height)
        fill.addColorStop(0, layer.color); fill.addColorStop(1, c2)
        context.globalAlpha = layer.alpha
        context.fillStyle = fill
        context.beginPath()
        context.moveTo(0, height)
        for (let x = 0; x <= width; x += 6 * ratio) {
          const u = x / width
          const y = base + Math.sin(u * 5.2 + t * layer.speed * 2 + layer.phase) * swell * 0.6 + Math.sin(u * 9.4 - t * layer.speed * 1.4 + layer.phase) * swell * 0.4
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

  return <div className="live-bar" role="group" aria-label="语音通话">
    <button
      type="button" className="glass-button" onClick={onToggleFigure} aria-pressed={figureOpen}
      aria-label={figureOpen ? '收起角色形象' : '显示角色形象'}
    ><Smile size={22} /></button>
    <div className={`wave-pill ${muted ? 'muted' : ''}`}>
      <canvas ref={canvasRef} aria-hidden="true" />
      <span className="wave-label">{muted ? '麦克风已关' : label}</span>
    </div>
    <button
      type="button" className="glass-button" onClick={onToggleMute} aria-pressed={muted}
      aria-label={muted ? '打开麦克风' : '关闭麦克风'}
    >{muted ? <MicOff size={22} /> : <Mic size={22} />}</button>
    <button type="button" className="glass-button end" onClick={onEnd} aria-label="结束实时通话"><X size={24} /></button>
  </div>
}
