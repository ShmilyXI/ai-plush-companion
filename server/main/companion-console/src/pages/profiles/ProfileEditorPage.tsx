import { ArrowLeftOutlined, HistoryOutlined, SaveOutlined, SoundOutlined, UndoOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Divider, Form, Input, List, Modal, Radio, Select, Space, Spin, Switch, Typography, message } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { cueNames, getProfile, listProfileModelOptions, listProfileVersions, restorePrompt, updateProfile, type CompanionProfile, type ProfileModelBinding, type ProfileModelOption, type ProfileUpdateInput, type ProfileVersion } from '../../api/profiles'
import { listModelVoices, type ModelVoice } from '../../api/xiaozhiModels'
import { ProfileModelSettings } from './ProfileModelSettings'

const cueLabels = { laugh: '开心轻笑', sigh: '轻轻叹息', hesitate: '犹豫停顿', breathe: '安定呼吸' } as const
const versionPageSize = 10

function voiceLanguages(value: string | null) {
  return value?.split(/[、,，;；/|\s]+/).map((item) => item.trim()).filter(Boolean) ?? []
}

function previewUrl(value: string) {
  try {
    const url = new URL(value, window.location.origin)
    return (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname ? url.href : null
  } catch {
    return null
  }
}

function releaseAudio(audio: HTMLAudioElement) {
  audio.onerror = null
  audio.pause()
  audio.currentTime = 0
  audio.removeAttribute('src')
  audio.load()
}

function orderedUniqueVersions(items: ProfileVersion[]) {
  return Array.from(new Map(items.map((item) => [item.id, item])).values())
    .sort((left, right) => right.versionNo - left.versionNo)
}

function sourceLabel(source: string) {
  if (source === 'initial') return '初始版本'
  if (source === 'companion-restore-prompt') return '恢复初始提示词'
  if (source === 'companion-update') return '角色设置更新'
  return '配置记录'
}

function ttsResourceId(binding: ProfileModelBinding | undefined, options: ProfileModelOption[]) {
  if (binding?.source === 'global') return binding.resourceId ?? ''
  if (binding?.source !== 'default') return ''
  const defaults = options.filter((option) => option.modelType === 'TTS' && option.source === 'global'
    && option.enabled && option.isDefault)
  return defaults.length === 1 ? defaults[0].id : ''
}

export function ProfileEditorPage() {
  const { id = '' } = useParams()
  const profileId = id
  const [form] = Form.useForm<Omit<ProfileUpdateInput, 'models'>>()
  const [profile, setProfile] = useState<CompanionProfile | null>(null)
  const [versions, setVersions] = useState<ProfileVersion[]>([])
  const [versionTotal, setVersionTotal] = useState(0)
  const [versionPage, setVersionPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
  const [voices, setVoices] = useState<ModelVoice[]>([])
  const [voiceLanguage, setVoiceLanguage] = useState('')
  const [modelOptions, setModelOptions] = useState<ProfileModelOption[]>([])
  const [modelBindings, setModelBindings] = useState<ProfileModelBinding[]>([])
  const [modelsChanged, setModelsChanged] = useState(false)
  const [ttsModelChanged, setTtsModelChanged] = useState(false)
  const [voiceChanged, setVoiceChanged] = useState(false)
  const [modelSaveError, setModelSaveError] = useState('')
  const [voiceLoadStatus, setVoiceLoadStatus] = useState<'idle' | 'loading' | 'loaded' | 'failed'>('idle')
  const [voiceError, setVoiceError] = useState('')
  const [previewError, setPreviewError] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const [restoreOpen, setRestoreOpen] = useState(false)
  const [error, setError] = useState('')
  const [messageApi, messageContext] = message.useMessage()
  const mounted = useRef(false)
  const loadSequence = useRef(0)
  const mutationSequence = useRef(0)
  const loadController = useRef<AbortController | null>(null)
  const mutationController = useRef<AbortController | null>(null)
  const mutationBusy = useRef(false)
  const historyController = useRef<AbortController | null>(null)
  const voiceController = useRef<AbortController | null>(null)
  const voiceSequence = useRef(0)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const previewSequence = useRef(0)
  const historyGeneration = useRef(0)
  const profileSession = useRef(0)
  const historyAnchor = useRef<number | undefined>(undefined)
  const selectedVoiceId = Form.useWatch('ttsVoiceId', form) ?? ''

  const loadNativeVoices = useCallback(async (modelId: string, currentVoiceId = '') => {
    const sequence = ++voiceSequence.current
    voiceController.current?.abort()
    setVoices([])
    setVoiceLanguage('')
    setVoiceError('')
    setPreviewError('')
    if (!modelId) {
      setVoiceLoadStatus('idle')
      return
    }
    const controller = new AbortController()
    voiceController.current = controller
    setVoiceLoadStatus('loading')
    try {
      const voiceList = await listModelVoices(modelId, undefined, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || voiceSequence.current !== sequence) return
      setVoices(voiceList)
      setVoiceLoadStatus('loaded')
      if (currentVoiceId && !voiceList.some((voice) => voice.id === currentVoiceId)) form.setFieldValue('ttsVoiceId', '')
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && voiceSequence.current === sequence) {
        setVoiceError(reason instanceof Error ? reason.message : '声音列表加载失败')
        setVoiceLoadStatus('failed')
      }
    }
  }, [form])

  const refreshHistory = useCallback(async () => {
    const generation = ++historyGeneration.current
    historyController.current?.abort()
    const controller = new AbortController()
    historyController.current = controller
    setLoadingMore(false)
    try {
      const result = await listProfileVersions(profileId, 1, versionPageSize, undefined, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || historyGeneration.current !== generation) return
      const nextVersions = orderedUniqueVersions(result.list)
      const anchor = nextVersions.reduce<number | undefined>((highest, item) => highest === undefined ? item.versionNo : Math.max(highest, item.versionNo), undefined)
      historyAnchor.current = anchor
      setVersions(nextVersions)
      setVersionTotal(result.total)
      setVersionPage(1)
    } catch {
      if (!mounted.current || controller.signal.aborted || historyGeneration.current !== generation) return
      historyAnchor.current = undefined
      setVersions([])
      setVersionTotal(0)
      setVersionPage(1)
    }
  }, [profileId])

  const load = useCallback(async () => {
    const sequence = ++loadSequence.current
    loadController.current?.abort()
    const controller = new AbortController()
    loadController.current = controller
    setLoading(true)
    setError('')
    void refreshHistory()
    let next: CompanionProfile
    let nextModelOptions: ProfileModelOption[]
    try {
      const result = await Promise.all([
        getProfile(profileId, { signal: controller.signal }),
        listProfileModelOptions(profileId, { signal: controller.signal }),
      ])
      next = result[0]
      nextModelOptions = result[1]
    } catch (reason) {
      if (!mounted.current || controller.signal.aborted || loadSequence.current !== sequence) return
      setError(reason instanceof Error ? reason.message : '角色加载失败')
      setLoading(false)
      return
    }
    if (!mounted.current || controller.signal.aborted || loadSequence.current !== sequence) return
    setProfile(next)
    setModelOptions(nextModelOptions)
    setModelBindings(next.models)
    setModelsChanged(false)
    setTtsModelChanged(false)
    setVoiceChanged(false)
    setModelSaveError('')
    const ttsBinding = next.models.find((binding) => binding.modelType === 'TTS')
    const ttsModelId = ttsResourceId(ttsBinding, nextModelOptions)
    const ttsVoiceId = ttsModelId ? next.ttsVoiceId ?? '' : ''
    form.setFieldsValue({
      name: next.name, relationMode: next.relationMode, userAddress: next.userAddress,
      personality: next.personality, systemPrompt: next.systemPrompt, ttsVoiceId,
      companionCues: next.companionCues, screenExpressionEnabled: next.screenExpressionEnabled,
      cameraPreferenceEnabled: next.cameraPreferenceEnabled,
    })
    setLoading(false)
    void loadNativeVoices(ttsModelId, ttsVoiceId)
  }, [form, loadNativeVoices, profileId, refreshHistory])

  useEffect(() => {
    profileSession.current += 1
    mounted.current = true
    setProfile(null)
    form.resetFields()
    setModelOptions([])
    setModelBindings([])
    setModelsChanged(false)
    setTtsModelChanged(false)
    setVoiceChanged(false)
    setModelSaveError('')
    setVoices([])
    setVoiceLanguage('')
    setVoiceLoadStatus('idle')
    setVoiceError('')
    setPreviewError('')
    setVersions([])
    setVersionTotal(0)
    setVersionPage(1)
    setLoadingMore(false)
    setSaving(false)
    setRestoring(false)
    setRestoreOpen(false)
    mutationBusy.current = false
    void load()
    return () => {
      profileSession.current += 1
      mounted.current = false
      loadSequence.current += 1
      mutationSequence.current += 1
      historyGeneration.current += 1
      voiceSequence.current += 1
      previewSequence.current += 1
      loadController.current?.abort()
      mutationController.current?.abort()
      historyController.current?.abort()
      voiceController.current?.abort()
      const audio = audioRef.current
      if (audio) {
        releaseAudio(audio)
        audioRef.current = null
      }
    }
  }, [form, load])

  async function save(values: Omit<ProfileUpdateInput, 'models'>) {
    if (mutationBusy.current) return
    if (modelsChanged && modelBindings.some((binding) => binding.source === 'private')) {
      setModelSaveError('请先替换所有已停用的个人模型')
      return
    }
    const sequence = ++mutationSequence.current
    const session = profileSession.current
    mutationController.current?.abort()
    const controller = new AbortController()
    mutationController.current = controller
    mutationBusy.current = true
    setSaving(true)
    try {
      const { ttsVoiceId, ...ordinaryValues } = values
      const input: ProfileUpdateInput = {
        ...ordinaryValues,
        ...(modelsChanged ? { models: modelBindings } : {}),
        ...(ttsModelChanged || voiceChanged ? { ttsVoiceId: ttsVoiceId ?? '' } : {}),
      }
      await updateProfile(profileId, input, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || mutationSequence.current !== sequence || profileSession.current !== session) return
      setProfile((current) => current ? { ...current, ...ordinaryValues,
        ...(input.ttsVoiceId !== undefined ? { ttsVoiceId: input.ttsVoiceId || null } : {}),
        ...(input.models ? { models: input.models } : {}) } : current)
      if (input.models) setModelsChanged(false)
      if (input.ttsVoiceId !== undefined) {
        setTtsModelChanged(false)
        setVoiceChanged(false)
      }
      setModelSaveError('')
      messageApi.success('角色设置已保存')
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && mutationSequence.current === sequence && profileSession.current === session) messageApi.error(reason instanceof Error ? reason.message : '保存失败')
    } finally {
      if (mounted.current && mutationSequence.current === sequence && profileSession.current === session) {
        mutationBusy.current = false
        setSaving(false)
      }
    }
  }

  function changeModels(next: ProfileModelBinding[], changed: ProfileModelBinding) {
    setModelBindings(next)
    setModelsChanged(true)
    if (changed.modelType === 'TTS') {
      setTtsModelChanged(true)
      setVoiceChanged(false)
    }
    setModelSaveError('')
    if (changed.modelType !== 'TTS') return
    stopPreview()
    setPreviewError('')
    form.setFieldValue('ttsVoiceId', '')
    void loadNativeVoices(ttsResourceId(changed, modelOptions))
  }

  function stopPreview() {
    previewSequence.current += 1
    const audio = audioRef.current
    if (!audio) return
    releaseAudio(audio)
  }

  async function previewVoice(voice: ModelVoice) {
    if (!voice.voiceDemo) return
    stopPreview()
    const sequence = previewSequence.current
    const source = previewUrl(voice.voiceDemo)
    if (!source) {
      setPreviewError('试听失败')
      return
    }
    const audio = audioRef.current ?? new Audio()
    audioRef.current = audio
    audio.onerror = () => {
      if (mounted.current && previewSequence.current === sequence && audio.src === source) {
        releaseAudio(audio)
        setPreviewError('试听失败')
      }
    }
    audio.src = source
    setPreviewError('')
    try {
      await audio.play()
    } catch {
      if (mounted.current && previewSequence.current === sequence) {
        releaseAudio(audio)
        setPreviewError('试听失败')
      }
    }
  }

  async function confirmRestore() {
    if (mutationBusy.current) return
    const sequence = ++mutationSequence.current
    const session = profileSession.current
    mutationController.current?.abort()
    const controller = new AbortController()
    mutationController.current = controller
    mutationBusy.current = true
    setRestoring(true)
    try {
      await restorePrompt(profileId, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || mutationSequence.current !== sequence || profileSession.current !== session) return
      const historyPromise = refreshHistory()
      const restoredProfile = await getProfile(profileId, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || mutationSequence.current !== sequence || profileSession.current !== session) return
      form.setFieldValue('systemPrompt', restoredProfile.systemPrompt)
      setProfile((current) => current ? { ...current, systemPrompt: restoredProfile.systemPrompt } : current)
      setRestoreOpen(false)
      await historyPromise
      if (mounted.current && profileSession.current === session) messageApi.success('完整提示词已恢复')
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && mutationSequence.current === sequence && profileSession.current === session) messageApi.error(reason instanceof Error ? reason.message : '恢复失败')
    } finally {
      if (mounted.current && mutationSequence.current === sequence && profileSession.current === session) {
        mutationBusy.current = false
        setRestoring(false)
      }
    }
  }

  function openRestore() {
    if (!mutationBusy.current) setRestoreOpen(true)
  }

  async function loadMoreVersions() {
    const nextPage = versionPage + 1
    const generation = historyGeneration.current
    const anchor = historyAnchor.current
    if (anchor === undefined) return
    historyController.current?.abort()
    const controller = new AbortController()
    historyController.current = controller
    setLoadingMore(true)
    try {
      const result = await listProfileVersions(profileId, nextPage, versionPageSize, anchor, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || historyGeneration.current !== generation) return
      setVersions((current) => orderedUniqueVersions([...current, ...result.list]))
      setVersionTotal(result.total)
      setVersionPage(nextPage)
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && historyGeneration.current === generation) messageApi.error(reason instanceof Error ? reason.message : '版本记录加载失败')
    } finally {
      if (mounted.current && !controller.signal.aborted && historyGeneration.current === generation) setLoadingMore(false)
    }
  }

  const languageOptions = Array.from(new Set(voices.flatMap((voice) => voiceLanguages(voice.languages))))
  const filteredVoices = voiceLanguage
    ? voices.filter((voice) => voiceLanguages(voice.languages).includes(voiceLanguage))
    : voices
  const selectedVoice = voices.find((voice) => voice.id === selectedVoiceId)

  return (
    <section className="console-page profile-editor-page">
      {messageContext}
      <Link className="back-link" to="/profiles"><ArrowLeftOutlined />返回角色列表</Link>
      {error && <Alert type="error" showIcon message={error} action={<Button size="small" onClick={() => void load()}>重试</Button>} />}
      <Spin spinning={loading}>
        {profile && <Form form={form} layout="vertical" onFinish={(values) => void save(values)}>
          <div className="page-heading">
            <div><Typography.Title level={1}>编辑陪伴角色</Typography.Title><Typography.Paragraph>日常设置和高级提示词分开管理，改起来更安心。</Typography.Paragraph></div>
            <Button aria-label="保存角色" type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving}
              disabled={saving || restoring}>保存角色</Button>
          </div>
          <div className="profile-editor-grid">
            <Card className="surface-card" title="日常设置">
              <Form.Item label="角色名称" name="name" rules={[{ required: true, whitespace: true, message: '请输入角色名称' }, { max: 64 }]}><Input maxLength={64} /></Form.Item>
              <Form.Item label="陪伴关系" name="relationMode"><Radio.Group optionType="button" options={[{ label: '治愈型朋友', value: 'friend' }, { label: '治愈型恋人', value: 'lover' }]} /></Form.Item>
              <Form.Item label="怎么称呼你" name="userAddress" rules={[{ max: 64 }]}><Input maxLength={64} placeholder="例如 小夏" /></Form.Item>
              <Form.Item label="性格" name="personality" rules={[{ max: 1000 }]}><Input.TextArea rows={4} maxLength={1000} showCount /></Form.Item>
              {languageOptions.length > 0 && <Form.Item label="语言">
                <Select aria-label="语言" value={voiceLanguage || undefined} allowClear placeholder="全部语言"
                  options={languageOptions.map((language) => ({ label: language, value: language }))}
                  onChange={(language = '') => {
                    setVoiceLanguage(language)
                    const currentVoiceId = form.getFieldValue('ttsVoiceId')
                    const currentVoice = voices.find((voice) => voice.id === currentVoiceId)
                    if (language && currentVoice && !voiceLanguages(currentVoice.languages).includes(language)) form.setFieldValue('ttsVoiceId', '')
                  }} />
              </Form.Item>}
              <Form.Item label="声音" name="ttsVoiceId" rules={[{
                validator: (_, value) => {
                  if (!ttsModelChanged && !voiceChanged) return Promise.resolve()
                  if (voiceLoadStatus === 'loading') return Promise.reject(new Error('声音列表加载中，请稍后保存'))
                  if (voiceLoadStatus === 'failed') return Promise.reject(new Error('声音列表加载失败，请重试'))
                  return !voices.length || voices.some((voice) => voice.id === value)
                    ? Promise.resolve()
                    : Promise.reject(new Error('请选择当前 TTS 模型的声音'))
                },
              }]}>
                <Select loading={voiceLoadStatus === 'loading'} options={filteredVoices.map((voice) => ({ label: voice.name, value: voice.id }))}
                  onChange={() => { setVoiceChanged(true); stopPreview(); setPreviewError('') }} />
              </Form.Item>
              {selectedVoice?.voiceDemo && <Button aria-label={`试听${selectedVoice.name}`} icon={<SoundOutlined />}
                onClick={() => void previewVoice(selectedVoice)}>试听</Button>}
              {voiceError && <Alert type="warning" showIcon message={voiceError} />}
              {previewError && <Alert type="warning" showIcon message={previewError} />}
              <Divider orientation="left">互动偏好</Divider>
              <div className="cue-grid">{cueNames.map((cue) => <Form.Item key={cue} label={cueLabels[cue]} name={['companionCues', cue]} valuePropName="checked"><Switch /></Form.Item>)}</div>
              <Form.Item label="使用屏幕表情" name="screenExpressionEnabled" valuePropName="checked"><Switch /></Form.Item>
              <Form.Item label="允许相机偏好" name="cameraPreferenceEnabled" valuePropName="checked"><Switch /></Form.Item>
            </Card>
            <Card className="surface-card advanced-prompt-card" title="高级提示词" extra={<Button aria-label="恢复初始提示词" icon={<UndoOutlined />} loading={restoring}
              disabled={saving || restoring} onClick={openRestore}>恢复初始提示词</Button>}>
              <Alert type="info" showIcon message="这里决定角色完整的说话方式和行为边界。恢复操作只影响此处。" />
              <Form.Item label="完整提示词" name="systemPrompt" rules={[{ max: 16000 }]}><Input.TextArea rows={18} maxLength={16000} showCount /></Form.Item>
            </Card>
            <Card className="surface-card profile-model-card" title="AI 模型">
              {modelSaveError && <Alert type="warning" showIcon message={modelSaveError} />}
              <ProfileModelSettings value={modelBindings} options={modelOptions} onChange={changeModels} />
            </Card>
            <Card className="surface-card version-card" title={<Space><HistoryOutlined />版本记录</Space>}>
              <List dataSource={versions} locale={{ emptyText: '暂无版本记录' }} renderItem={(version) => <List.Item><List.Item.Meta title={`版本 ${version.versionNo} · ${sourceLabel(version.source)}`} description={new Date(version.createdAt).toLocaleString('zh-CN')} /></List.Item>} />
              {versionPage * versionPageSize < versionTotal && <Button block loading={loadingMore} onClick={() => void loadMoreVersions()}>加载更多版本</Button>}
            </Card>
          </div>
        </Form>}
      </Spin>
      <Modal title="恢复初始提示词" open={restoreOpen} confirmLoading={restoring} okText="确认恢复" cancelText="取消"
        okButtonProps={{ disabled: saving || restoring }} onCancel={() => setRestoreOpen(false)} onOk={() => void confirmRestore()}>
        <Typography.Paragraph>只恢复完整提示词，不会覆盖名称、关系、声音、设备绑定和历史记录。</Typography.Paragraph>
      </Modal>
    </section>
  )
}
