export const MIN_SAMPLE_SECONDS = 8
export const MAX_SAMPLE_SECONDS = 60
export const MAX_DECODED_PCM_BYTES = 32 * 1024 * 1024
export const MAX_WAV_BYTES = 10 * 1024 * 1024
const ESTIMATED_SAMPLE_RATE = 48_000
const ESTIMATED_CHANNELS = 2

function assertDuration(duration: number) {
  if (!Number.isFinite(duration) || duration < MIN_SAMPLE_SECONDS || duration > MAX_SAMPLE_SECONDS) {
    throw new RangeError('音频时长必须在 8 到 60 秒之间')
  }
  const estimate = duration * ESTIMATED_SAMPLE_RATE * ESTIMATED_CHANNELS * Float32Array.BYTES_PER_ELEMENT
  if (estimate > MAX_DECODED_PCM_BYTES) throw new RangeError('音频解码内存预计超限')
}

export function readSafeVoiceMetadata(file: File, signal?: AbortSignal, createMedia = () => document.createElement('audio')) {
  return new Promise<number>((resolve, reject) => {
    const media = createMedia()
    const url = URL.createObjectURL(file)
    let settled = false
    const cleanup = () => {
      media.onloadedmetadata = null
      media.onerror = null
      signal?.removeEventListener('abort', abort)
      media.removeAttribute('src')
      media.load()
      URL.revokeObjectURL(url)
    }
    const finish = (action: () => void) => {
      if (settled) return
      settled = true
      cleanup()
      action()
    }
    const abort = () => finish(() => reject(new DOMException('音频读取已取消', 'AbortError')))
    media.onloadedmetadata = () => finish(() => {
      try { assertDuration(media.duration); resolve(media.duration) } catch (reason) { reject(reason) }
    })
    media.onerror = () => finish(() => reject(new TypeError('无法读取音频元数据')))
    signal?.addEventListener('abort', abort, { once: true })
    if (signal?.aborted) return abort()
    media.preload = 'metadata'
    media.src = url
    media.load()
  })
}

export function assertSafeDecodedBuffer(buffer: AudioBuffer) {
  assertDuration(buffer.duration)
  const bytes = buffer.length * buffer.numberOfChannels * Float32Array.BYTES_PER_ELEMENT
  if (!Number.isSafeInteger(bytes) || bytes > MAX_DECODED_PCM_BYTES) throw new RangeError('解码后的音频过大')
}

export function encodeVoiceSampleWav(buffer: AudioBuffer) {
  assertSafeDecodedBuffer(buffer)
  const dataLength = buffer.length * buffer.numberOfChannels * 2
  if (44 + dataLength > MAX_WAV_BYTES) throw new RangeError('WAV 音频不能超过 10MB')
  const result = new ArrayBuffer(44 + dataLength)
  const view = new DataView(result)
  let position = 0
  const write16 = (value: number) => { view.setUint16(position, value, true); position += 2 }
  const write32 = (value: number) => { view.setUint32(position, value, true); position += 4 }
  write32(0x46464952); write32(result.byteLength - 8); write32(0x45564157); write32(0x20746d66); write32(16)
  write16(1); write16(buffer.numberOfChannels); write32(buffer.sampleRate); write32(buffer.sampleRate * buffer.numberOfChannels * 2)
  write16(buffer.numberOfChannels * 2); write16(16); write32(0x61746164); write32(dataLength)
  const channels = Array.from({ length: buffer.numberOfChannels }, (_, index) => buffer.getChannelData(index))
  for (let frame = 0; frame < buffer.length; frame += 1) for (const channel of channels) {
    const sample = Math.max(-1, Math.min(1, channel[frame] ?? 0))
    view.setInt16(position, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true)
    position += 2
  }
  return new File([result], 'voice-sample.wav', { type: 'audio/wav' })
}

export function trimVoiceBuffer(context: AudioContext, buffer: AudioBuffer, start: number, end: number) {
  const from = Math.floor(Math.min(start, end) * buffer.length)
  const to = Math.ceil(Math.max(start, end) * buffer.length)
  if (to <= from) throw new RangeError('请选择有效音频片段')
  const trimmed = context.createBuffer(buffer.numberOfChannels, to - from, buffer.sampleRate)
  for (let channel = 0; channel < buffer.numberOfChannels; channel += 1) {
    trimmed.getChannelData(channel).set(buffer.getChannelData(channel).subarray(from, to))
  }
  assertSafeDecodedBuffer(trimmed)
  return trimmed
}
