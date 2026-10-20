import { useEffect, useRef, useState } from 'react'
import { fetchScreenImage } from '../../api/screenScenes'
import { decodeRgb565, screenAssetId } from '../../screen-composer/rgb565'

/** Draws an uploaded screen image exactly as the device receives it (RGB565, already letterboxed). */
export function ScreenImagePreview({ resource }: { resource?: string }) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [error, setError] = useState<string | null>(null)
  const id = screenAssetId(resource)

  useEffect(() => {
    if (!id) return
    let cancelled = false
    setError(null)
    fetchScreenImage(id).then((buffer) => {
      const canvas = canvasRef.current
      const context = canvas?.getContext('2d')
      if (cancelled || !canvas || !context) return
      const { width, height, rgba } = decodeRgb565(buffer)
      canvas.width = width
      canvas.height = height
      context.putImageData(new ImageData(new Uint8ClampedArray(rgba), width, height), 0, 0)
    }).catch((reason: unknown) => { if (!cancelled) setError(reason instanceof Error ? reason.message : '图片加载失败') })
    return () => { cancelled = true }
  }, [id])

  if (!id) return <span className="screen-composer-placeholder">{resource || '未选择图片'}</span>
  if (error) return <span className="screen-composer-placeholder">{error}</span>
  return <canvas ref={canvasRef} className="screen-composer-image" />
}
