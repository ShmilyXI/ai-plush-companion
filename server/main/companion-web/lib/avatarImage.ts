/** 头像存成多大的正方形（像素）。界面上最大显示 88 像素，256 在高清屏上也够清楚，文件只有几十 KB。 */
export const AVATAR_SIZE = 256
/** 选的原图最大多少。只是防止误选了超大的文件，真正存的是缩小后的图。 */
const MAX_SOURCE_BYTES = 20 * 1024 * 1024

/** 只认我们自己生成的那种 data URL，别的（比如外链）一律不显示。 */
export function isAvatarDataUrl(value: unknown): value is string {
  return typeof value === 'string' && /^data:image\/(jpeg|png|webp);base64,/.test(value)
}

function loadImage(url: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('这张图片打不开，换一张试试'))
    image.src = url
  })
}

/**
 * 把用户选的图片裁成居中的正方形，缩到 256 像素，转成 JPEG 的 data URL。
 * 透明的地方填白色，免得 JPEG 里变成黑色。手机拍的照片的方向浏览器解码时已经摆正了。
 */
export async function avatarFromFile(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) throw new Error('请选择一张图片')
  if (file.size > MAX_SOURCE_BYTES) throw new Error('图片太大了，换一张 20 MB 以内的')
  const url = URL.createObjectURL(file)
  try {
    const image = await loadImage(url)
    const side = Math.min(image.naturalWidth, image.naturalHeight)
    if (!side) throw new Error('这张图片打不开，换一张试试')
    const canvas = document.createElement('canvas')
    canvas.width = AVATAR_SIZE; canvas.height = AVATAR_SIZE
    const context = canvas.getContext('2d')
    if (!context) throw new Error('这个浏览器处理不了图片，换个浏览器试试')
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, AVATAR_SIZE, AVATAR_SIZE)
    context.imageSmoothingQuality = 'high'
    context.drawImage(image, (image.naturalWidth - side) / 2, (image.naturalHeight - side) / 2, side, side, 0, 0, AVATAR_SIZE, AVATAR_SIZE)
    return canvas.toDataURL('image/jpeg', 0.86)
  } finally {
    URL.revokeObjectURL(url)
  }
}
