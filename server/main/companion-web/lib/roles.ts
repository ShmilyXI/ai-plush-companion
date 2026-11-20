/**
 * 角色在网页端的样子：头像、列表里的一句短介绍、第一次聊天时给用户的几句开场话。
 *
 * 服务端目前不保存头像，这里按角色名字对应到三个预设角色的形象。
 * 名字对不上的角色（管理台里新加的预设、从管理台过来的角色）用名字的第一个字和一个底色。
 */
/** 3D 玩偶的样子：是哪种动物，以及各部分的颜色。 */
export interface PlushSpec {
  kind: 'rabbit' | 'cat' | 'bear'
  /** 身体的颜色。 */
  fur: string
  /** 耳朵里面的颜色。 */
  inner: string
  /** 嘴周围和肚子的浅色（小熊用）。 */
  accent: string
  nose: string
}

export interface RoleLook {
  /** 头像图片地址。没有时用名字的第一个字。 */
  avatar?: string
  /** 全身形象的图片，透明底。登录和选角色时用，3D 角色画不出来时也用它。 */
  body?: string
  /** 3D 角色的样子。没有时用一只颜色取自 aura 的小熊。 */
  plush?: PlushSpec
  /** 这个角色的光：页面的环境光、通话的声浪和头像的底都用这三种颜色。 */
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
    plush: { kind: 'rabbit', fur: '#cbb8e8', inner: '#efe2f6', accent: '#efe2f6', nose: '#b98aa8' },
    aura: ['#c9b6ff', '#7b5cff', '#ff8fd0'],
    tagline: '温柔的倾听者',
    starters: ['今天有点累', '想找人说说话', '最近睡不太好'],
    tint: '#e4daf2',
  },
  阿澈: {
    avatar: '/roles/ache-face.webp',
    body: '/roles/ache-body.webp',
    plush: { kind: 'cat', fur: '#8f9bc8', inner: '#f3b9c4', accent: '#dfe4f4', nose: '#e89aa8' },
    aura: ['#9fe0ff', '#3f6bff', '#6ff2d4'],
    tagline: '冷静直接的朋友',
    starters: ['帮我理一理这件事', '我有点拿不定主意', '想听听你的看法'],
    tint: '#d6dfeb',
  },
  小满: {
    avatar: '/roles/xiaoman-face.webp',
    body: '/roles/xiaoman-body.webp',
    plush: { kind: 'bear', fur: '#f2b95c', inner: '#c98a3c', accent: '#fae3b0', nose: '#6b3f25' },
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

/** 用户可以给角色选的颜色。每种颜色带着配套的玩偶配色和页面的光。 */
export const LOOK_COLORS: Array<{ key: string; label: string; plush: Omit<PlushSpec, 'kind'>; aura: [string, string, string] }> = [
  { key: 'lilac', label: '丁香紫', plush: { fur: '#cbb8e8', inner: '#efe2f6', accent: '#efe2f6', nose: '#b98aa8' }, aura: ['#c9b6ff', '#7b5cff', '#ff8fd0'] },
  { key: 'blue', label: '雾蓝', plush: { fur: '#8f9bc8', inner: '#f3b9c4', accent: '#dfe4f4', nose: '#e89aa8' }, aura: ['#9fe0ff', '#3f6bff', '#6ff2d4'] },
  { key: 'honey', label: '蜜黄', plush: { fur: '#f2b95c', inner: '#c98a3c', accent: '#fae3b0', nose: '#6b3f25' }, aura: ['#ffe08a', '#ff8a3d', '#ff6aa0'] },
  { key: 'peach', label: '桃粉', plush: { fur: '#f4aebb', inner: '#fbe0e4', accent: '#fde9ec', nose: '#b0566a' }, aura: ['#ffc2d6', '#ff5c8a', '#ffb36b'] },
  { key: 'mint', label: '薄荷绿', plush: { fur: '#a3d8bc', inner: '#e3f5ea', accent: '#e9f7ef', nose: '#4f8a70' }, aura: ['#b6ffd9', '#19b98a', '#7ad7ff'] },
  { key: 'cream', label: '奶白', plush: { fur: '#efe5d6', inner: '#e4c9b0', accent: '#fbf6ee', nose: '#8a6a55' }, aura: ['#ffe9c7', '#c9a26b', '#ffd0b0'] },
]
export const LOOK_KINDS: Array<{ key: PlushSpec['kind']; label: string }> = [
  { key: 'rabbit', label: '兔子' }, { key: 'cat', label: '猫' }, { key: 'bear', label: '小熊' },
]

/** 用户给角色选的形象：哪种动物、哪种颜色。 */
export interface CustomLook { kind: PlushSpec['kind']; color: string }

// 用户自己选的形象，按角色名字登记。选过之后，这个角色到处都用它，预设的图片不再用。
const CUSTOM = new Map<string, CustomLook>()

/** 登记或取消一个角色自己选的形象。 */
export function setCustomLook(name: string, look: CustomLook | null) {
  if (look) CUSTOM.set(name.trim(), look)
  else CUSTOM.delete(name.trim())
}

export function customLook(name: string): CustomLook | null {
  return CUSTOM.get(name.trim()) ?? null
}

/** 一个角色现在是哪种动物、哪种颜色：自己选过的按选的，预设角色按预设，其他的是蜜黄色的小熊。 */
export function currentLook(name: string): CustomLook {
  const custom = customLook(name)
  if (custom) return custom
  const preset = PRESETS[name.trim()]
  const color = LOOK_COLORS.find((item) => item.plush.fur === preset?.plush?.fur)?.key ?? 'honey'
  return { kind: preset?.plush?.kind ?? 'bear', color }
}

export function roleLook(name: string): RoleLook {
  const key = name.trim()
  const preset = PRESETS[key]
  const custom = CUSTOM.get(key)
  const color = custom ? LOOK_COLORS.find((item) => item.key === custom.color) : undefined
  if (custom && color) {
    return {
      plush: { kind: custom.kind, ...color.plush }, aura: color.aura, tint: color.plush.fur,
      tagline: preset?.tagline, starters: preset?.starters ?? DEFAULT_STARTERS,
    }
  }
  if (preset) return preset
  let hash = 0
  for (const char of key) hash = (hash * 31 + (char.codePointAt(0) ?? 0)) >>> 0
  return { starters: DEFAULT_STARTERS, tint: TINTS[hash % TINTS.length], aura: AURAS[hash % AURAS.length] }
}

/** 编辑角色时预览用的名字：改形象时先登记在它名下，保存了才算数。 */
export const PREVIEW_NAME = '\u0000preview'

const lookKey = (agentId: string) => `companion.look.${agentId}`

/**
 * 用户给某个角色选的形象记在这个浏览器里（服务端目前没有地方存）。换一台设备会回到默认的样子。
 */
export function loadSavedLook(agentId: string): CustomLook | null {
  try {
    const saved = JSON.parse(window.localStorage.getItem(lookKey(agentId)) || 'null') as Partial<CustomLook> | null
    if (!saved) return null
    const kind = LOOK_KINDS.find((item) => item.key === saved.kind)?.key
    const color = LOOK_COLORS.find((item) => item.key === saved.color)?.key
    return kind && color ? { kind, color } : null
  } catch { return null }
}

export function saveLook(agentId: string, look: CustomLook | null) {
  try {
    if (look) window.localStorage.setItem(lookKey(agentId), JSON.stringify(look))
    else window.localStorage.removeItem(lookKey(agentId))
  } catch { /* 存不了就只在这次生效 */ }
}
