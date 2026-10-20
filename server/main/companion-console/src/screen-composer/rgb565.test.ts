import { describe, expect, it } from 'vitest'
import { decodeRgb565, screenAssetId } from './rgb565'

function image(width: number, height: number, pixels: number[]) {
  const bytes = new Uint8Array(12 + pixels.length * 2)
  bytes.set([0x19, 0x04, 0, 0, width & 0xff, width >> 8, height & 0xff, height >> 8, (width * 2) & 0xff, (width * 2) >> 8, 0, 0])
  pixels.forEach((value, i) => { bytes[12 + i * 2] = value & 0xff; bytes[13 + i * 2] = value >> 8 })
  return bytes.buffer
}

describe('decodeRgb565', () => {
  it('expands little-endian RGB565 pixels to RGBA', () => {
    const decoded = decodeRgb565(image(2, 1, [0xf800, 0x07e0]))
    expect(decoded.width).toBe(2)
    expect(Array.from(decoded.rgba)).toEqual([255, 0, 0, 255, 0, 255, 0, 255])
  })

  it('rejects wrong magic and truncated data', () => {
    const bad = new Uint8Array(image(1, 1, [0]))
    bad[0] = 0
    expect(() => decodeRgb565(bad.buffer)).toThrow()
    expect(() => decodeRgb565(image(2, 2, [0, 0]))).toThrow()
  })
})

describe('screenAssetId', () => {
  const id = '123e4567-e89b-12d3-a456-426614174000'
  it('reads both the resource id and a legacy URL', () => {
    expect(screenAssetId(`screen-asset:${id}`)).toBe(id)
    expect(screenAssetId(`http://x/zixuan/companion/screen-scenes/resources/${id}`)).toBe(id)
    expect(screenAssetId('assets/neutral.eaf')).toBeNull()
  })
})
