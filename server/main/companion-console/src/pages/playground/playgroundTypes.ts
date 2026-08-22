export type PlaygroundInputKind = 'text' | 'audio' | 'vision' | 'activity'
export type PlaygroundEventStatus = 'started' | 'completed' | 'failed'

export interface PlaygroundEvent {
  sessionId: string
  sequence: number
  capability: string
  stage: string
  status: PlaygroundEventStatus
  startedAt: number
  finishedAt: number | null
  durationMs: number | null
  inputSummary: string
  outputSummary: string
  error: string | null
}

export interface PlaygroundMessage { id: string; role: 'user' | 'assistant' | 'event'; text: string; createdAt: string; capability?: string }
export interface VirtualDeviceState { width: number; height: number; depth: number; orientation: string; screen: boolean; camera: boolean; microphone: boolean; activitySensor: boolean }
export interface PlaygroundSnapshotSummary { profileId: string; profileName: string; models: Record<string, string>; ttsVoiceId: string | null; skills: string[]; virtualDevice: VirtualDeviceState }
export interface PlaygroundSession { id: string; title: string; createdAt: string; playgroundSessionId: string | null; runtimeCursor?: number; snapshot: PlaygroundSnapshotSummary; messages: PlaygroundMessage[]; events: PlaygroundEvent[]; screenState: Record<string, unknown>; memories: string[] }
export interface PlaygroundStore { version: 1; sessions: PlaygroundSession[]; activeSessionId: string | null; updatedAt: string }
