export interface DecodedRgb565 {
  width: number
  height: number
  rgba: Uint8ClampedArray
}

const HEADER_BYTES = 12
const GFX_MAGIC = 0x19
const GFX_RGB565 = 0x04

/** Decode the device image format written by ScreenSceneController.upload (12-byte gfx header + little-endian RGB565). */
export function decodeRgb565(buffer: ArrayBuffer): DecodedRgb565 {
  if (buffer.byteLength < HEADER_BYTES) throw new Error('图片数据过短')
  const view = new DataView(buffer)
  if (view.getUint8(0) !== GFX_MAGIC || view.getUint8(1) !== GFX_RGB565) throw new Error('不是设备 RGB565 图片')
  const width = view.getUint16(4, true)
  const height = view.getUint16(6, true)
  const stride = view.getUint16(8, true)
  if (!width || !height || stride !== width * 2) throw new Error('图片头信息无效')
  if (buffer.byteLength < HEADER_BYTES + stride * height) throw new Error('图片数据不完整')
  const rgba = new Uint8ClampedArray(width * height * 4)
  for (let i = 0; i < width * height; i++) {
    const value = view.getUint16(HEADER_BYTES + i * 2, true)
    const r = (value >>> 11) & 0x1f
    const g = (value >>> 5) & 0x3f
    const b = value & 0x1f
    rgba[i * 4] = (r << 3) | (r >>> 2)
    rgba[i * 4 + 1] = (g << 2) | (g >>> 4)
    rgba[i * 4 + 2] = (b << 3) | (b >>> 2)
    rgba[i * 4 + 3] = 255
  }
  return { width, height, rgba }
}

/** "screen-asset:<id>" or a legacy ".../resources/<id>" URL -> the asset id, otherwise null. */
export function screenAssetId(resource: string | undefined): string | null {
  const match = /(?:^screen-asset:|\/companion\/screen-scenes\/resources\/)([0-9a-fA-F-]{36})$/.exec(resource || '')
  return match ? match[1] : null
}
