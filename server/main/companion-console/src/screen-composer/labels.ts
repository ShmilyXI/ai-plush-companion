import type { ScreenMode, ScreenState } from './sceneTypes'

export const modeLabels: Record<ScreenMode, string> = {
  static_image: '静态图', chat: '对话', emotion_chat: '表情对话', text_chat: '纯文字对话', custom: '自定义展示',
}

export const stateLabels: Record<ScreenState, string> = {
  idle: '待机', listening: '聆听', thinking: '思考', speaking: '说话', error: '出错',
}
