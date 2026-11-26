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

/** 2D 角色的图。blink 和 talk 是只有眼睛、嘴那一小块的透明图，叠在 base 上。 */
export interface RoleArt { base: string; blink?: string; talk?: string }

/** 每种动物的图。颜色是画好的：兔子是浅紫，猫是灰蓝，小熊是蜜黄。 */
const ART: Record<PlushSpec['kind'], { art: RoleArt; face: string }> = {
  rabbit: { art: { base: '/roles/rabbit.webp', blink: '/roles/rabbit-blink.webp', talk: '/roles/rabbit-talk.webp' }, face: '/roles/rabbit-face.webp' },
  cat: { art: { base: '/roles/cat.webp', blink: '/roles/cat-blink.webp', talk: '/roles/cat-talk.webp' }, face: '/roles/cat-face.webp' },
  bear: { art: { base: '/roles/bear.webp', blink: '/roles/bear-blink.webp', talk: '/roles/bear-talk.webp' }, face: '/roles/bear-face.webp' },
}

/**
 * Live2D 模型。现在用的是 Live2D 官方的示例模型，文件不在仓库里，用 scripts/fetch-live2d.py 下载到 public/live2d/。
 * 用的是脚本生成的 .web.model3.json：贴图换成了无损 WebP，下载量约减半。
 *
 * 示例模型和我们角色的样子对不上，所以默认不用它们：角色用自己的 2D 图（见 RoleCharacter）。
 * 以后有了为某个角色定制的模型，在这里加一项并标上 custom: true、把那个角色指过去，它就会用 Live2D。
 */
export const LIVE2D_MODELS: Array<{ key: string; label: string; url: string; custom?: boolean }> = [
  { key: 'hiyori', label: 'Hiyori', url: '/live2d/hiyori/Hiyori.web.model3.json' },
  { key: 'mao', label: 'Mao', url: '/live2d/mao/Mao.web.model3.json' },
  { key: 'mark', label: 'Mark', url: '/live2d/mark/Mark.web.model3.json' },
]

export function live2dModel(key: string | undefined) {
  return LIVE2D_MODELS.find((item) => item.key === key) ?? LIVE2D_MODELS[0]
}

/** 这个角色有没有为它定制的 Live2D 模型。只有定制的模型才和角色的样子一致。 */
export function hasCustomLive2D(name: string) {
  const key = roleLook(name).live2d
  return LIVE2D_MODELS.some((item) => item.key === key && item.custom === true)
}

export interface RoleLook {
  /** 头像图片地址。没有时用名字的第一个字。 */
  avatar?: string
  /** 全身形象的图片，透明底。登录和选角色时用，3D 角色画不出来时也用它。 */
  body?: string
  /** 2D 角色用的几张图：全身像，以及叠在上面的闭眼和张嘴。 */
  art?: RoleArt
  /** 用哪个 Live2D 模型（LIVE2D_MODELS 里的 key）。没有时用第一个。 */
  live2d?: string
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

/** 预设角色的一套装扮：换的是图和页面的光，名字、性格、开场白不变。 */
export interface RoleOutfit {
  key: string
  label: string
  look: Pick<RoleLook, 'avatar' | 'body' | 'art' | 'aura' | 'tint'>
}

function outfitArt(file: string, aura: RoleLook['aura'], tint: string): RoleOutfit['look'] {
  return {
    avatar: `/roles/${file}-face.webp`, body: `/roles/${file}.webp`, aura, tint,
    art: { base: `/roles/${file}.webp`, blink: `/roles/${file}-blink.webp`, talk: `/roles/${file}-talk.webp` },
  }
}

/**
 * 有几套装扮的预设角色，第一套是默认的。用户在角色设置里换，记在这台设备上（见 loadSavedOutfit）。
 * 年糕的猫娘形象来自设定稿「墨婉 / MOWA」：主播装、大小姐装、银发卫衣和 Q 版；橘猫卫衣是她原来的样子。
 */
const OUTFITS: Record<string, RoleOutfit[]> = {
  年糕: [
    { key: 'host', label: '主播装', look: outfitArt('niangao-host', ['#fff1c9', '#d9a441', '#8fe0c4'], '#efe8dc') },
    { key: 'lady', label: '大小姐', look: outfitArt('niangao-lady', ['#fbe8c4', '#b8893a', '#e8b8c8'], '#ece4d6') },
    { key: 'silver', label: '银发卫衣', look: outfitArt('niangao-silver', ['#e4f6ee', '#5cc4a4', '#b9c4d4'], '#e2ebe7') },
    { key: 'chibi', label: 'Q 版', look: outfitArt('niangao-chibi', ['#d8fbef', '#3fc9a4', '#ffd27a'], '#dff1ea') },
    { key: 'classic', label: '橘猫卫衣', look: outfitArt('niangao', ['#ffd2a0', '#ff8a3d', '#7fe0c8'], '#f8dcc0') },
  ],
}

function niangaoOutfit(key: string) {
  return OUTFITS.年糕.find((item) => item.key === key)!.look
}

const PRESETS: Record<string, RoleLook> = {
  紫萱: {
    avatar: ART.rabbit.face,
    body: ART.rabbit.art.base,
    art: ART.rabbit.art,
    live2d: 'hiyori',
    plush: { kind: 'rabbit', fur: '#cbb8e8', inner: '#efe2f6', accent: '#efe2f6', nose: '#b98aa8' },
    aura: ['#c9b6ff', '#7b5cff', '#ff8fd0'],
    tagline: '温柔的倾听者',
    starters: ['今天有点累', '想找人说说话', '最近睡不太好'],
    tint: '#e4daf2',
  },
  阿澈: {
    avatar: ART.cat.face,
    body: ART.cat.art.base,
    art: ART.cat.art,
    live2d: 'mark',
    plush: { kind: 'cat', fur: '#8f9bc8', inner: '#f3b9c4', accent: '#dfe4f4', nose: '#e89aa8' },
    aura: ['#9fe0ff', '#3f6bff', '#6ff2d4'],
    tagline: '冷静直接的朋友',
    starters: ['帮我理一理这件事', '我有点拿不定主意', '想听听你的看法'],
    tint: '#d6dfeb',
  },
  小满: {
    avatar: ART.bear.face,
    body: ART.bear.art.base,
    art: ART.bear.art,
    live2d: 'mao',
    plush: { kind: 'bear', fur: '#f2b95c', inner: '#c98a3c', accent: '#fae3b0', nose: '#6b3f25' },
    aura: ['#ffe08a', '#ff8a3d', '#ff6aa0'],
    tagline: '开朗好奇的搭子',
    starters: ['跟你说个今天的事', '猜猜我今天吃了什么', '给我讲个冷知识'],
    tint: '#f4e3b4',
  },
  苏念: {
    avatar: '/roles/sunian-face.webp',
    body: '/roles/sunian.webp',
    art: { base: '/roles/sunian.webp', blink: '/roles/sunian-blink.webp', talk: '/roles/sunian-talk.webp' },
    // 还没有为她定制的 Live2D 模型，先用同样是少女形象的 Hiyori 示例模型。
    live2d: 'hiyori',
    aura: ['#e6ccff', '#b07ae8', '#ff8fb8'],
    tagline: '爱脸红的女朋友',
    starters: ['今天想你了', '你的新书写到哪了', '跟你说晚安'],
    tint: '#ead8f0',
  },
  // 年糕是半身的猫娘立绘，有几套装扮（见 OUTFITS），这里是默认那套。没有定制的 Live2D 模型：先用 Mao（示例模型里最活泼的一个）占位。
  年糕: {
    ...niangaoOutfit('host'),
    live2d: 'mao',
    plush: { kind: 'cat', fur: '#d9d6d2', inner: '#f6d2d8', accent: '#fbf8f2', nose: '#e08a96' },
    tagline: '自称舒适之神的懒猫',
    starters: ['今天的舒服分是几分', '我又熬夜了', '猜猜我晚饭吃了啥'],
  },
  晚舟: {
    avatar: '/roles/wanzhou-face.webp',
    body: '/roles/wanzhou.webp',
    art: { base: '/roles/wanzhou.webp', blink: '/roles/wanzhou-blink.webp', talk: '/roles/wanzhou-talk.webp' },
    live2d: 'mark',
    aura: ['#ffe3b8', '#d99a4e', '#3f5a8f'],
    tagline: '深夜小茶馆的店主，听你慢慢说',
    starters: ['今天有点撑不住', '睡不着，来坐坐', '给我配一杯吧'],
    tint: '#efe0c8',
  },
  江屿: {
    avatar: '/roles/jiangyu-face.webp',
    body: '/roles/jiangyu.webp',
    art: { base: '/roles/jiangyu.webp', blink: '/roles/jiangyu-blink.webp', talk: '/roles/jiangyu-talk.webp' },
    // 没有为他定制的 Live2D 模型。mark 是现有示例里唯一的男性形象，所以用它。
    live2d: 'mark',
    aura: ['#bfe3ff', '#4f8dff', '#8fe9ff'],
    tagline: '水族馆的饲养员，阳光又有点害羞',
    starters: ['报告一下我今天的事', '今天有水母的新八卦吗', '跟你说晚安'],
    tint: '#d3e5f7',
  },
  小乐: {
    avatar: '/roles/xiaole-face.webp',
    body: '/roles/xiaole.webp',
    art: { base: '/roles/xiaole.webp', blink: '/roles/xiaole-blink.webp', talk: '/roles/xiaole-talk.webp' },
    // 没有她自己的 Live2D 模型。Hiyori 是棕发的年轻女孩，和她最接近；以后有了定制模型再换。
    live2d: 'hiyori',
    aura: ['#ffe27a', '#4f86d6', '#ffb1a0'],
    tagline: '爱听老故事的孙女',
    starters: ['给你讲讲我年轻时候', '陪我说说话', '今天天气怎么样'],
    tint: '#f6e7a8',
  },
  秋白: {
    // 真人比例的 2D 立绘，没有对应的 Live2D 模型，也没有玩偶；live2d 先借 Hiyori（黑发的女生，气质最接近）。
    avatar: '/roles/qiubai-face.webp',
    body: '/roles/qiubai.webp',
    art: { base: '/roles/qiubai.webp', blink: '/roles/qiubai-blink.webp', talk: '/roles/qiubai-talk.webp' },
    live2d: 'hiyori',
    aura: ['#7ff3e4', '#12b5a6', '#2b2f3a'],
    tagline: '干练的生活助理',
    starters: ['帮我排一下明天', '这周买菜清单', '周末出门要带什么'],
    tint: '#cfe9e6',
  },
  枫糖: {
    // 圆滚滚的小狐狸，没有定制的 Live2D 模型。Mao 是示例模型里最活泼、带点小淘气的一个，和她爱闹的性格最搭，先用它。
    avatar: '/roles/maple-face.webp',
    body: '/roles/maple.webp',
    art: { base: '/roles/maple.webp', blink: '/roles/maple-blink.webp', talk: '/roles/maple-talk.webp' },
    live2d: 'mao',
    aura: ['#ffd9a8', '#ff8a3d', '#2fb8a8'],
    tagline: '陪你练英语的小狐狸',
    starters: ['今天的松果是什么', '陪我练口语', '来一段点咖啡的对练'],
    tint: '#fde4c8',
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
export interface CustomLook {
  kind: PlushSpec['kind']
  color: string
  /** 选的 Live2D 模型。只在启用 Live2D 时有用。 */
  model?: string
  /** 选的 Mowa Bot 角色（它的 cast id，比如 hop、bruno）。选了就用 Mowa Bot 画这个角色，见 lib/mowa.ts。 */
  mowa?: string
  /** Mowa Bot 的画风（vector、pixel、kaomoji、ascii）。没有时是矢量。 */
  mowaStyle?: string
  /** 画风里的质感，比如矢量的 watercolor、crayon。没有时用画风自己的默认。 */
  mowaVariant?: string
  /** Mowa Bot 的脸型，比如 cute、sparkle。没有时用这个角色自己的脸。 */
  mowaFace?: string
}

// 用户自己选的形象，按角色名字登记。选过之后，这个角色到处都用它，预设的图片不再用。
const CUSTOM = new Map<string, CustomLook>()

/** 这个名字是不是一个有自己专门形象的预设角色。 */
export function hasPresetLook(name: string) {
  return Boolean(PRESETS[name.trim()])
}

/**
 * 登记或取消一个角色自己选的形象。
 * 预设角色总是用为它画的形象，不登记：以前在这台设备上给它选过动物的，也回到它自己的样子。
 */
export function setCustomLook(name: string, look: CustomLook | null) {
  if (look && !hasPresetLook(name)) CUSTOM.set(name.trim(), look)
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
  return { kind: preset?.plush?.kind ?? 'bear', color, model: preset?.live2d }
}

// 用户给预设角色选的装扮，按角色名字登记。没选过的用第一套。
const OUTFIT = new Map<string, string>()
// 角色设置里预览的装扮：预设角色的名字和选中的那套。
let previewOutfit: { name: string; key: string } | null = null

/** 这个角色有哪几套装扮。没有的是空的。 */
export function roleOutfits(name: string): RoleOutfit[] {
  return OUTFITS[name.trim()] ?? []
}

/** 登记或取消一个预设角色选的装扮。不认识的装扮当作没选。 */
export function setOutfit(name: string, key: string | null) {
  if (key && roleOutfits(name).some((item) => item.key === key)) OUTFIT.set(name.trim(), key)
  else OUTFIT.delete(name.trim())
}

/** 这个角色现在穿的是哪套。没有装扮可选的角色是 undefined。 */
export function currentOutfit(name: string): string | undefined {
  return OUTFIT.get(name.trim()) ?? roleOutfits(name)[0]?.key
}

/** 角色设置里预览某个预设角色的某套装扮，画在 PREVIEW_NAME 名下。传 null 取消。 */
export function setPreviewOutfit(name: string | null, key?: string) {
  previewOutfit = name && key ? { name: name.trim(), key } : null
}

function withOutfit(preset: RoleLook, name: string, key: string | undefined): RoleLook {
  const outfit = roleOutfits(name).find((item) => item.key === key)
  return outfit ? { ...preset, ...outfit.look } : preset
}

export function roleLook(name: string): RoleLook {
  const key = name.trim()
  if (key === PREVIEW_NAME && previewOutfit && PRESETS[previewOutfit.name]) {
    return withOutfit(PRESETS[previewOutfit.name], previewOutfit.name, previewOutfit.key)
  }
  const preset = PRESETS[key]
  const custom = CUSTOM.get(key)
  const color = custom ? LOOK_COLORS.find((item) => item.key === custom.color) : undefined
  if (custom && color) {
    return {
      plush: { kind: custom.kind, ...color.plush }, aura: color.aura, tint: color.plush.fur,
      avatar: ART[custom.kind].face, body: ART[custom.kind].art.base, art: ART[custom.kind].art,
      live2d: custom.model ?? preset?.live2d,
      tagline: preset?.tagline, starters: preset?.starters ?? DEFAULT_STARTERS,
    }
  }
  if (preset) return withOutfit(preset, key, currentOutfit(key))
  let hash = 0
  for (const char of key) hash = (hash * 31 + (char.codePointAt(0) ?? 0)) >>> 0
  // 没有专门形象、用户也没选过的角色：头像是名字的第一个字，通话时出来的是小熊。
  return { starters: DEFAULT_STARTERS, tint: TINTS[hash % TINTS.length], aura: AURAS[hash % AURAS.length], art: ART.bear.art, live2d: LIVE2D_MODELS[hash % LIVE2D_MODELS.length].key }
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
    const model = LIVE2D_MODELS.find((item) => item.key === saved.model)?.key
    // 这里不引入 Mowa Bot 的角色表，只认格式；认不出的角色在 MowaCharacter 里回到动物的样子。
    const id = (value: unknown) => typeof value === 'string' && /^[a-z][a-z0-9-]{0,31}$/.test(value) ? value : undefined
    const mowa = id(saved.mowa)
    const extras = mowa ? {
      mowa,
      ...(id(saved.mowaStyle) ? { mowaStyle: id(saved.mowaStyle) } : {}),
      ...(id(saved.mowaVariant) ? { mowaVariant: id(saved.mowaVariant) } : {}),
      ...(id(saved.mowaFace) ? { mowaFace: id(saved.mowaFace) } : {}),
    } : {}
    return kind && color ? { kind, color, ...(model ? { model } : {}), ...extras } : null
  } catch { return null }
}

export function saveLook(agentId: string, look: CustomLook | null) {
  try {
    if (look) window.localStorage.setItem(lookKey(agentId), JSON.stringify(look))
    else window.localStorage.removeItem(lookKey(agentId))
  } catch { /* 存不了就只在这次生效 */ }
}

const outfitKey = (agentId: string) => `companion.outfit.${agentId}`

/** 用户给某个预设角色选的装扮，和形象一样记在这个浏览器里。 */
export function loadSavedOutfit(agentId: string): string | null {
  try { return window.localStorage.getItem(outfitKey(agentId)) } catch { return null }
}

export function saveOutfit(agentId: string, key: string | null) {
  try {
    if (key) window.localStorage.setItem(outfitKey(agentId), key)
    else window.localStorage.removeItem(outfitKey(agentId))
  } catch { /* 存不了就只在这次生效 */ }
}
