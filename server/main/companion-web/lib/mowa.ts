/**
 * Mowa Bot 角色：用代码画的 SVG 小角色（vendor/ 里的 @mowa-bot 包，基于 Aora Bot 改造）。
 *
 * 这个文件只放纯数据的对应关系，不引入 Mowa Bot 的运行时，这样聊天页面在不用 Mowa Bot 时不会多加载它。
 * 真正画角色的是 components/MowaCharacter.tsx 和 components/MowaStill.tsx。
 */
import type { CharacterState, InkPaint, ShapeId } from '@mowa-bot/engine'
import type { CharacterMood } from '../components/Character'
import { LOOK_COLORS, currentLook, type PlushSpec } from './roles'

/** 服务端回复开头的情绪标记（zixuan-server 的 CompanionEmotion），通过 reply.emotion 事件发过来。 */
export type ReplyEmotion = 'neutral' | 'happy' | 'gentle' | 'sad' | 'surprised' | 'sleepy' | 'concerned'

const REPLY_EMOTIONS: readonly ReplyEmotion[] = ['neutral', 'happy', 'gentle', 'sad', 'surprised', 'sleepy', 'concerned']

/** 把事件里的情绪读成已知的值。认不出的当作没有。 */
export function parseReplyEmotion(value: unknown): ReplyEmotion | null {
  return REPLY_EMOTIONS.includes(value as ReplyEmotion) ? value as ReplyEmotion : null
}

/** 每种情绪在说话时用哪个动画状态。neutral 不在这里：没有情绪时说话用 SPEAKING_STATE。 */
const EMOTION_STATES: Record<Exclude<ReplyEmotion, 'neutral'>, CharacterState> = {
  happy: 'happy',
  // 温柔的话多是安慰和陪伴，用眼睛弯起来的满足样子，不跳。
  gentle: 'happy',
  sad: 'sad',
  surprised: 'surprised',
  sleepy: 'drowsy',
  // 担心的时候凑近一点仔细看。
  concerned: 'curious',
}

/**
 * 说话但没有情绪时的样子：眼睛往下看一点，嘴一张一合像在说话。
 *
 * 不用 dictating：它是 Mowa Bot 的「腰带」状态，身体缩成一个没有脸的小球、外面绕一圈声波，
 * 说话时整张脸就没了。说话要用身体还在、嘴会动的状态（引擎的 STATE_MOUTH_TALK 里不在 STATE_BELT 的那些）。
 */
export const SPEAKING_STATE: CharacterState = 'composing'

/** 说完之后情绪表情再留多久（毫秒），然后回到平常的样子。只发文字、不出声的回复也靠它显出表情。 */
export const EMOTION_LINGER_MS = 4000

/**
 * 闲着时的几个阶段：刚连上时伸个懒腰（waking），一阵子没动静开始东张西望（bored），再久一点犯困（drowsy）。
 * 有任何动静（用户说话、角色回复、断线）就回到 rest。
 */
export type IdleStage = 'rest' | 'waking' | 'bored' | 'drowsy'

/** 刚连上时伸懒腰演多久（毫秒）。 */
export const WAKING_MS = 1800
/** 闲了多久开始东张西望。 */
export const BORED_AFTER_MS = 45_000
/** 闲了多久开始犯困。 */
export const DROWSY_AFTER_MS = 180_000

/**
 * 角色此刻用哪个动画状态。
 *
 * 通话状态决定大的样子：断线在睡、用户说话时在听、等回复时在想。
 * 角色说话时用这条回复的情绪；说完以后 lingering 为 true 的那几秒里还保持这个情绪。
 */
export function mowaState(mood: CharacterMood, emotion: ReplyEmotion | null, lingering = false, stage: IdleStage = 'rest'): CharacterState {
  if (mood === 'offline') return 'sleeping'
  if (mood === 'hearing') return 'listening'
  if (mood === 'thinking') return 'thinking'
  const felt = emotion && emotion !== 'neutral' ? EMOTION_STATES[emotion] : null
  if (mood === 'speaking') return felt ?? SPEAKING_STATE
  if (lingering && felt) return felt
  return stage === 'rest' ? 'idle' : stage
}

/** 这条情绪到来时要不要跳一下。 */
export function emotionHops(emotion: ReplyEmotion | null) {
  return emotion === 'happy' || emotion === 'surprised'
}

/** 网页里的三种动物对应 Mowa Bot 的身体造型。 */
const SHAPES: Record<PlushSpec['kind'], ShapeId> = { rabbit: 'bunny', cat: 'cat', bear: 'bear' }

/** 从手绘换到 Mowa Bot 时先选中的角色：和原来是同一种动物。 */
export const DEFAULT_CAST: Record<PlushSpec['kind'], string> = { rabbit: 'hop', cat: 'miso', bear: 'bruno' }

/** 角色设置里能挑的画风，以及每种画风的质感。名字取自 Mowa Bot 自己的中文说明。 */
export const MOWA_STYLES: Array<{ key: string; label: string; variants: Array<{ key: string; label: string }> }> = [
  { key: 'vector', label: '矢量', variants: [
    { key: 'solid', label: '扁平' }, { key: 'soft', label: '软糖' }, { key: 'glass', label: '玻璃' }, { key: 'sticker', label: '贴纸' },
    { key: 'paper', label: '剪纸' }, { key: 'outline', label: '线条' }, { key: 'watercolor', label: '水彩' }, { key: 'crayon', label: '蜡笔' },
    { key: 'pencil', label: '铅笔' }, { key: 'marker', label: '涂鸦' }, { key: 'chalk', label: '粉笔' }, { key: 'riso', label: '孔版' },
    { key: 'origami', label: '折纸' }, { key: 'relief', label: '浮雕' }, { key: 'neon', label: '霓虹' }, { key: 'gothic', label: '暗黑手绘' },
  ] },
  { key: 'pixel', label: '像素', variants: [{ key: 'crisp', label: '清晰' }, { key: 'dot', label: '点阵' }, { key: 'block', label: '方块' }] },
  { key: 'kaomoji', label: '颜文字', variants: [{ key: 'plain', label: '纯文字' }, { key: 'bubble', label: '气泡' }] },
  { key: 'ascii', label: '字符画', variants: [{ key: 'ansi', label: '彩色' }, { key: 'mono', label: '单色' }, { key: 'crt', label: '终端' }] },
]

/** 能挑的脸型。第一项「原样」表示用角色自己的脸。 */
export const MOWA_FACES: Array<{ key: string; label: string }> = [
  { key: '', label: '原样' },
  { key: 'cute', label: '萌脸' }, { key: 'eyes', label: '豆豆眼' }, { key: 'line', label: '线描脸' }, { key: 'sparkle', label: '闪亮眼' },
  { key: 'glossy', label: '水汪眼' }, { key: 'capsule', label: '胶囊眼' }, { key: 'button', label: '纽扣眼' }, { key: 'droopy', label: '半睁眼' },
  { key: 'teardrop', label: '水滴眼' }, { key: 'brow', label: '浓眉眼' }, { key: 'bigmouth', label: '大嘴脸' }, { key: 'pie', label: '派切眼' },
  { key: 'symbol', label: '符号眼' }, { key: 'specs', label: '眼镜脸' }, { key: 'shades', label: '墨镜脸' }, { key: 'screen', label: '屏幕脸' },
]

/** 角色设置的预览下面可以点着看的表情。 */
export const MOWA_DEMO_STATES: Array<{ key: CharacterState; label: string }> = [
  { key: 'happy', label: '开心' }, { key: 'laughing', label: '大笑' }, { key: 'shy', label: '害羞' }, { key: 'surprised', label: '惊讶' },
  { key: 'curious', label: '好奇' }, { key: 'sad', label: '难过' }, { key: 'angry', label: '生气' }, { key: 'confused', label: '困惑' },
  { key: 'drowsy', label: '犯困' }, { key: 'thinking', label: '思考' }, { key: 'listening', label: '倾听' }, { key: 'celebrate', label: '庆祝' },
]

/** 点了预览下面的表情后，演多久（毫秒）再回到平常。 */
export const MOWA_DEMO_MS = 2600

/** 把颜色和白色按 amount（0 到 1）混合，用来做渐变的亮面。 */
function lighten(hex: string, amount: number) {
  const value = Number.parseInt(hex.slice(1), 16)
  const mix = (channel: number) => Math.round(channel + (255 - channel) * amount)
  const [r, g, b] = [(value >> 16) & 255, (value >> 8) & 255, value & 255].map(mix)
  return `#${((r << 16) | (g << 8) | b).toString(16).padStart(6, '0')}`
}

export interface MowaLook {
  /** 挑的 Mowa Bot 角色。没有时按动物画。 */
  cast?: string
  /** 动物对应的造型和角色自己的颜色。挑的角色在这个版本里不存在时也用它们画。 */
  shape: ShapeId
  ink: InkPaint
  /** 画风、质感和脸型。没挑时不给，用 Mowa Bot 的默认。 */
  style?: string
  variant?: string
  face?: string
}

/**
 * 一个角色画成 Mowa Bot 时的样子。
 *
 * 用户在角色设置里挑了 Mowa Bot 的某个角色（cast），就用那个角色本来的造型和颜色，加上挑的画风、质感和脸型。
 * 其余的按角色现在的形象：兔子、猫、小熊换成对应的造型，身体涂成角色自己的颜色，
 * 所以紫萱还是那只浅紫的兔子，只是换了一种画法。
 */
export function mowaLook(name: string): MowaLook {
  const look = currentLook(name)
  const fur = LOOK_COLORS.find((item) => item.key === look.color)?.plush.fur ?? LOOK_COLORS[0].plush.fur
  return {
    ...(look.mowa ? { cast: look.mowa } : {}),
    shape: SHAPES[look.kind],
    ink: { from: lighten(fur, 0.35), to: fur, angle: 135 },
    ...(look.mowa && look.mowaStyle ? { style: look.mowaStyle } : {}),
    ...(look.mowa && look.mowaVariant ? { variant: look.mowaVariant } : {}),
    ...(look.mowa && look.mowaFace ? { face: look.mowaFace } : {}),
  }
}
