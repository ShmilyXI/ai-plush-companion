'use client'

import { useEffect, useRef } from 'react'
import { ArrowUp, AudioLines, Plus, X } from 'lucide-react'

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
  /** 连接是通的。发图片需要连接。 */
  connected: boolean
  /** 输入框里的提示，比如“和小满说点什么”。 */
  placeholder: string
  onSend: () => void
  onStartVoice: () => void
}

/** 底部的输入栏：加图片、打字，最右边是开始语音通话。写了内容才出现发送。 */
export default function Composer({ draft, onDraft, image, onImage, onProblem, canType, connected, placeholder, onSend, onStartVoice }: ComposerProps) {
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
    {image && <div className="attachment">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={image} alt="准备发送的图片" />
      <button type="button" className="attachment-remove" aria-label="移除图片" onClick={() => onImage(null)}><X size={14} /></button>
    </div>}
    <div className="composer-row">
      <label className={`tool-button ${connected ? '' : 'disabled'}`} aria-label="添加图片">
        <Plus size={24} />
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
        placeholder={image ? '给图片加一句话…' : placeholder} disabled={!canType}
      />
      {hasContent && <button type="button" className="send-button" onClick={onSend} disabled={!canType} aria-label="发送"><ArrowUp size={22} /></button>}
      <button type="button" className="voice-button" onClick={onStartVoice} disabled={!connected} aria-label="开始实时通话"><AudioLines size={22} /></button>
    </div>
  </div>
}
