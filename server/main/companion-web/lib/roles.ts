/**
 * 角色在网页端的样子：头像、列表里的一句短介绍、第一次聊天时给用户的几句开场话。
 *
 * 服务端目前不保存头像，这里按角色名字对应到三个预设角色的形象。
 * 名字对不上的角色（管理台里新加的预设、从管理台过来的角色）用名字的第一个字和一个底色。
 */
export interface RoleLook {
  /** 头像图片地址。没有时用名字的第一个字。 */
  avatar?: string
  /** 舞台上的全身形象，透明底。没有时舞台上只有光球。 */
  body?: string
  /** 这个角色的光：光球、页面的环境光和选中状态都用这三种颜色。 */
  aura: [string, string, string]
  /** 角色列表里名字下面的一句话。 */
  tagline?: string
  /** 还没和这个角色说过话时，输入框上方给出的几句话，点一下就发出去。 */
  starters: string[]
  /** 没有头像时的底色。 */
  tint: string
}

const DEFAULT_STARTERS = ['今天过得一般', '随便聊聊', '给我讲个冷知识']

const PRESETS: Record<string, RoleLook> = {
  紫萱: {
    avatar: '/roles/zixuan-face.webp',
    body: '/roles/zixuan-body.webp',
    aura: ['#c9b6ff', '#7b5cff', '#ff8fd0'],
    tagline: '温柔的倾听者',
    starters: ['今天有点累', '想找人说说话', '最近睡不太好'],
    tint: '#e4daf2',
  },
  阿澈: {
    avatar: '/roles/ache-face.webp',
    body: '/roles/ache-body.webp',
    aura: ['#9fe0ff', '#3f6bff', '#6ff2d4'],
    tagline: '冷静直接的朋友',
    starters: ['帮我理一理这件事', '我有点拿不定主意', '想听听你的看法'],
    tint: '#d6dfeb',
  },
  小满: {
    avatar: '/roles/xiaoman-face.webp',
    body: '/roles/xiaoman-body.webp',
    aura: ['#ffe08a', '#ff8a3d', '#ff6aa0'],
    tagline: '开朗好奇的搭子',
    starters: ['跟你说个今天的事', '猜猜我今天吃了什么', '给我讲个冷知识'],
    tint: '#f4e3b4',
  },
}

const TINTS = ['#e4daf2', '#d6dfeb', '#f4e3b4', '#d9e8d6', '#f2d8d0', '#d5e8e6']
const AURAS: Array<[string, string, string]> = [
  ['#c9b6ff', '#7b5cff', '#ff8fd0'],
  ['#9fe0ff', '#3f6bff', '#6ff2d4'],
  ['#ffe08a', '#ff8a3d', '#ff6aa0'],
  ['#b6ffd9', '#19b98a', '#7ad7ff'],
  ['#ffc2d6', '#ff5c8a', '#ffb36b'],
]

export function roleLook(name: string): RoleLook {
  const key = name.trim()
  const preset = PRESETS[key]
  if (preset) return preset
  let hash = 0
  for (const char of key) hash = (hash * 31 + (char.codePointAt(0) ?? 0)) >>> 0
  return { starters: DEFAULT_STARTERS, tint: TINTS[hash % TINTS.length], aura: AURAS[hash % AURAS.length] }
}
