/**
 * Mowa Bot 角色：用代码画的 SVG 小角色（vendor/ 里的 @mowa-bot 包，基于 Aora Bot 改造）。
 *
 * 这个文件只放纯数据的对应关系，不引入 Mowa Bot 的运行时，这样聊天页面在不用 Mowa Bot 时不会多加载它。
 * 真正画角色的是 components/MowaCharacter.tsx。
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

/** 说话但没有情绪时的样子：身边的声波随说话起伏。 */
export const SPEAKING_STATE: CharacterState = 'dictating'

/** 说完之后情绪表情再留多久（毫秒），然后回到平常的样子。只发文字、不出声的回复也靠它显出表情。 */
export const EMOTION_LINGER_MS = 4000

/**
 * 角色此刻用哪个动画状态。
 *
 * 通话状态决定大的样子：断线在睡、用户说话时在听、等回复时在想。
 * 角色说话时用这条回复的情绪；说完以后 lingering 为 true 的那几秒里还保持这个情绪。
 */
export function mowaState(mood: CharacterMood, emotion: ReplyEmotion | null, lingering = false): CharacterState {
  if (mood === 'offline') return 'sleeping'
  if (mood === 'hearing') return 'listening'
  if (mood === 'thinking') return 'thinking'
  const felt = emotion && emotion !== 'neutral' ? EMOTION_STATES[emotion] : null
  if (mood === 'speaking') return felt ?? SPEAKING_STATE
  return lingering && felt ? felt : 'idle'
}

/** 这条情绪到来时要不要跳一下。 */
export function emotionHops(emotion: ReplyEmotion | null) {
  return emotion === 'happy' || emotion === 'surprised'
}

/** 网页里的三种动物对应 Mowa Bot 的身体造型。 */
const SHAPES: Record<PlushSpec['kind'], ShapeId> = { rabbit: 'bunny', cat: 'cat', bear: 'bear' }

/** 把颜色和白色按 amount（0 到 1）混合，用来做渐变的亮面。 */
function lighten(hex: string, amount: number) {
  const value = Number.parseInt(hex.slice(1), 16)
  const mix = (channel: number) => Math.round(channel + (255 - channel) * amount)
  const [r, g, b] = [(value >> 16) & 255, (value >> 8) & 255, value & 255].map(mix)
  return `#${((r << 16) | (g << 8) | b).toString(16).padStart(6, '0')}`
}

/**
 * 一个角色画成 Mowa Bot 时的样子。
 *
 * 用户在角色设置里挑了 Mowa Bot 的某个角色（cast），就用那个角色本来的造型和颜色。
 * 其余的按角色现在的形象：兔子、猫、小熊换成对应的造型，身体涂成角色自己的颜色，
 * 所以紫萱还是那只浅紫的兔子，只是换了一种画法。
 *
 * shape 和 ink 总是给出：挑的 cast 在这个版本的 Mowa Bot 里不存在时，用它们画回动物的样子。
 */
export function mowaLook(name: string): { cast?: string; shape: ShapeId; ink: InkPaint } {
  const look = currentLook(name)
  const fur = LOOK_COLORS.find((item) => item.key === look.color)?.plush.fur ?? LOOK_COLORS[0].plush.fur
  return {
    ...(look.mowa ? { cast: look.mowa } : {}),
    shape: SHAPES[look.kind],
    ink: { from: lighten(fur, 0.35), to: fur, angle: 135 },
  }
}
