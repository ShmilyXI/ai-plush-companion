'use client'

import { PhoneOff } from 'lucide-react'
import Avatar from './Avatar'

export type VoicePhase = 'waiting' | 'idle' | 'hearing' | 'thinking' | 'speaking'

interface VoiceStageProps {
  roleName: string
  phase: VoicePhase
  /** 最近的一句话：用户正在说的，或者角色刚说的。 */
  caption: string
  /** 这句话是谁说的。 */
  captionFrom: 'user' | 'assistant' | null
  onEnd: () => void
}

function phaseText(phase: VoicePhase, roleName: string) {
  if (phase === 'waiting') return '正在重新连接…'
  if (phase === 'hearing') return '在听你说…'
  if (phase === 'thinking') return `${roleName}在想…`
  if (phase === 'speaking') return `${roleName}在说，你可以随时打断`
  return '直接说话就可以'
}

/**
 * 语音通话：盖在对话上面，只留头像、当前状态和最近一句话。
 * 说过的话照常记在对话里，结束通话后都能看到。
 */
export default function VoiceStage({ roleName, phase, caption, captionFrom, onEnd }: VoiceStageProps) {
  return <section className={`voice-stage ${phase}`} aria-label="语音通话">
    <div className="voice-center">
      <div className="voice-avatar">
        <span className="voice-ring" aria-hidden="true" />
        <span className="voice-ring second" aria-hidden="true" />
        <Avatar name={roleName} size={168} motion />
      </div>
      <p className="voice-name">{roleName}</p>
      <p className="voice-phase" role="status">{phaseText(phase, roleName)}</p>
      <p className={`voice-caption ${captionFrom === 'user' ? 'from-user' : ''}`} aria-live="polite">
        <span>{caption}</span>
      </p>
    </div>
    <div className="voice-actions">
      <button type="button" className="hangup" onClick={onEnd} aria-label="结束实时通话"><PhoneOff size={26} /></button>
      <span className="voice-hint">结束通话</span>
    </div>
  </section>
}
