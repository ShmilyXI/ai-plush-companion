import { SoundOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Form, Select } from 'antd'

import type { ModelVoice } from '../../../api/zixuanModels'

type ProfileVoiceTabProps = {
  languageOptions: string[]
  voiceLanguage: string
  voices: ModelVoice[]
  filteredVoices: ModelVoice[]
  selectedVoice?: ModelVoice
  voiceLoadStatus: 'idle' | 'loading' | 'loaded' | 'failed'
  voiceError: string
  previewError: string
  validateVoice: (value: string | undefined) => Promise<void>
  onLanguageChange: (language: string) => void
  onVoiceChange: () => void
  onPreviewVoice: (voice: ModelVoice) => void
}

export function ProfileVoiceTab({ languageOptions, voiceLanguage, voices, filteredVoices, selectedVoice, voiceLoadStatus,
  voiceError, previewError, validateVoice, onLanguageChange, onVoiceChange, onPreviewVoice }: ProfileVoiceTabProps) {
  return <Card className="surface-card" title="声音与情绪">
    {languageOptions.length > 0 && <Form.Item label="语言">
      <Select aria-label="语言" value={voiceLanguage || undefined} allowClear placeholder="全部语言"
        options={languageOptions.map((language) => ({ label: language, value: language }))}
        onChange={(language = '') => onLanguageChange(language)} />
    </Form.Item>}
    <Form.Item label="声音" name="ttsVoiceId" rules={[{ validator: (_, value) => validateVoice(value) }]}>
      <Select loading={voiceLoadStatus === 'loading'} options={filteredVoices.map((voice) => ({ label: voice.name, value: voice.id }))}
        onChange={onVoiceChange} />
    </Form.Item>
    {selectedVoice?.voiceDemo && <Button aria-label={`试听${selectedVoice.name}`} icon={<SoundOutlined />}
      onClick={() => onPreviewVoice(selectedVoice)}>试听</Button>}
    {voiceError && <Alert type="warning" showIcon message={voiceError} />}
    {previewError && <Alert type="warning" showIcon message={previewError} />}
    {voices.length === 0 && voiceLoadStatus === 'loaded' && <span className="sr-only">当前 TTS 模型没有可选声音</span>}
  </Card>
}
