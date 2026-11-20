'use client'

import { useEffect, useRef } from 'react'
import { ImagePlus, Mic, Send, X } from 'lucide-react'

const MAX_IMAGE_BYTES = 6 * 1024 * 1024

interface ComposerProps {
  draft: string
  onDraft: (value: string) => void
  /** 已经选好、还没发出去的图片。 */
  image: string | null
  onImage: (dataUrl: string | null) => void
  onProblem: (message: string) => void
  /** 可以输入和发送。重连期间也可以，发出的话会在连上后补发。 */
  canType: boolean
  /** 连接是通的。图片和语音需要连接。 */
  connected: boolean
  /** 还没和这个角色说过话时给出的几句话。 */
  starters: string[]
  onSend: (quick?: string) => void
  onStartVoice: () => void
}

/**
 * 输入栏。没有内容时右边是麦克风（开始语音通话），有内容时变成发送。
 */
export default function Composer({
  draft, onDraft, image, onImage, onProblem, canType, connected, starters, onSend, onStartVoice,
}: ComposerProps) {
  const input = useRef<HTMLTextAreaElement>(null)
  const hasContent = Boolean(draft.trim()) || Boolean(image)

  // 输入框随内容长高，到上限后在框里滚动。
  useEffect(() => {
    const element = input.current
    if (!element) return
    element.style.height = 'auto'
    element.style.height = `${Math.min(element.scrollHeight, 132)}px`
  }, [draft])

  function pick(file: File | undefined) {
    if (!file) return
    if (file.size > MAX_IMAGE_BYTES) { onProblem('图片太大了，请选 6MB 以内的图片'); return }
    const reader = new FileReader()
    reader.onload = () => onImage(String(reader.result))
    reader.onerror = () => onProblem('图片读取失败，请换一张试试')
    reader.readAsDataURL(file)
  }

  return <div className="composer">
    {starters.length > 0 && canType && <div className="starters" aria-label="可以这样开始">
      {starters.map((text) => <button key={text} type="button" className="starter" onClick={() => onSend(text)}>{text}</button>)}
    </div>}
    {image && <div className="attachment">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={image} alt="准备发送的图片" />
      <button type="button" className="attachment-remove" aria-label="移除图片" onClick={() => onImage(null)}><X size={14} /></button>
    </div>}
    <div className="composer-row">
      <label className={`tool-button ${connected ? '' : 'disabled'}`} aria-label="添加图片">
        <ImagePlus size={22} />
        <input
          type="file" accept="image/*" hidden disabled={!connected}
          onChange={(event) => { pick(event.target.files?.[0]); event.target.value = '' }}
        />
      </label>
      <textarea
        ref={input} aria-label="输入消息" rows={1} enterKeyHint="send" value={draft}
        onChange={(event) => onDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); onSend() }
        }}
        placeholder={image ? '给图片加一句话…' : '说点什么…'} disabled={!canType}
      />
      {hasContent
        ? <button type="button" className="round-button primary" onClick={() => onSend()} disabled={!canType} aria-label="发送"><Send size={20} /></button>
        : <button type="button" className="round-button" onClick={onStartVoice} disabled={!connected} aria-label="开始实时通话"><Mic size={21} /></button>}
    </div>
  </div>
}
