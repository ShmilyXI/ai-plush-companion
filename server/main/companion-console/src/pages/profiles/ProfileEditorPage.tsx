import { SaveOutlined } from '@ant-design/icons'
import { PageContainer } from '@ant-design/pro-components'
import { Alert, Button, Form, Modal, Spin, Tabs, Typography, message } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'

import { getProfile, getProfileVersion, listProfileModelOptions, listProfileVersions, restorePrompt, updateProfile, type CompanionProfile, type ProfileModelBinding, type ProfileModelOption, type ProfileUpdateInput, type ProfileVersion } from '../../api/profiles'
import { modelTypes } from '../../api/models'
import { listModelVoices, type ModelVoice } from '../../api/xiaozhiModels'
import { ProfileBasicsTab } from './editor/ProfileBasicsTab'
import { ProfileCapabilitiesTab } from './editor/ProfileCapabilitiesTab'
import { ProfileModelsTab } from './editor/ProfileModelsTab'
import { ProfileVersionsTab } from './editor/ProfileVersionsTab'
import { ProfileVoiceTab } from './editor/ProfileVoiceTab'
import { useUnsavedProfileGuard } from './editor/useUnsavedProfileGuard'

const versionPageSize = 10
const profileTabs = ['basics', 'models', 'voice', 'capabilities', 'versions'] as const
type ProfileTab = typeof profileTabs[number]

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
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
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
  const [formChanged, setFormChanged] = useState(false)
  const [modelSaveError, setModelSaveError] = useState('')
  const [voiceLoadStatus, setVoiceLoadStatus] = useState<'idle' | 'loading' | 'loaded' | 'failed'>('idle')
  const [voiceError, setVoiceError] = useState('')
  const [previewError, setPreviewError] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const [restoreOpen, setRestoreOpen] = useState(false)
  const [selectedVersion, setSelectedVersion] = useState<ProfileVersion | null>(null)
  const [restoringVersionId, setRestoringVersionId] = useState('')
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
  const formRevision = useRef(0)
  const modelsRevision = useRef(0)
  const ttsModelRevision = useRef(0)
  const voiceRevision = useRef(0)
  const promptRevision = useRef(0)
  const selectedVoiceId = Form.useWatch('ttsVoiceId', form) ?? ''
  const requestedTab = searchParams.get('tab')
  const activeTab: ProfileTab = profileTabs.includes(requestedTab as ProfileTab) ? requestedTab as ProfileTab : 'basics'
  const dirty = formChanged || modelsChanged || ttsModelChanged || voiceChanged

  useUnsavedProfileGuard(dirty)

  useEffect(() => {
    if (requestedTab === activeTab) return
    const next = new URLSearchParams(searchParams)
    next.set('tab', activeTab)
    setSearchParams(next, { replace: true })
  }, [activeTab, requestedTab, searchParams, setSearchParams])

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
    setFormChanged(false)
    formRevision.current = 0
    modelsRevision.current = 0
    ttsModelRevision.current = 0
    voiceRevision.current = 0
    promptRevision.current = 0
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
    setFormChanged(false)
    formRevision.current = 0
    modelsRevision.current = 0
    ttsModelRevision.current = 0
    voiceRevision.current = 0
    promptRevision.current = 0
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
    setSelectedVersion(null)
    setRestoringVersionId('')
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
    const missingCredentialBinding = modelsChanged ? modelBindings.find((binding) => {
      if (binding.source !== 'global' || !binding.resourceId) return false
      const option = modelOptions.find((item) => item.source === 'global' && item.id === binding.resourceId
        && item.modelType === binding.modelType)
      return option?.credentialStatus === 'missing'
    }) : undefined
    if (missingCredentialBinding) {
      const option = modelOptions.find((item) => item.source === 'global' && item.id === missingCredentialBinding.resourceId
        && item.modelType === missingCredentialBinding.modelType)
      const name = option?.name || missingCredentialBinding.name || '所选模型'
      setModelSaveError(`${name} 未配置凭据，无法保存`)
      return
    }
    const sequence = ++mutationSequence.current
    const session = profileSession.current
    mutationController.current?.abort()
    const controller = new AbortController()
    mutationController.current = controller
    mutationBusy.current = true
    setSaving(true)
    const savedRevisions = {
      form: formRevision.current,
      models: modelsRevision.current,
      ttsModel: ttsModelRevision.current,
      voice: voiceRevision.current,
    }
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
      if (input.models && modelsRevision.current === savedRevisions.models) setModelsChanged(false)
      if (input.ttsVoiceId !== undefined) {
        if (ttsModelRevision.current === savedRevisions.ttsModel) setTtsModelChanged(false)
        if (voiceRevision.current === savedRevisions.voice) setVoiceChanged(false)
      }
      if (formRevision.current === savedRevisions.form) {
        form.resetFields()
        form.setFieldsValue(values)
        setFormChanged(false)
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
    modelsRevision.current += 1
    if (changed.modelType === 'TTS') {
      setTtsModelChanged(true)
      setVoiceChanged(false)
      ttsModelRevision.current += 1
    }
    setModelSaveError('')
    if (changed.modelType !== 'TTS') return
    stopPreview()
    setPreviewError('')
    formRevision.current += 1
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
    const restorePromptRevision = promptRevision.current
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
      if (promptRevision.current === restorePromptRevision) {
        formRevision.current += 1
        promptRevision.current += 1
        form.setFieldValue('systemPrompt', restoredProfile.systemPrompt)
        setFormChanged(true)
      }
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

  function openVersionRestore(version: ProfileVersion) {
    if (!mutationBusy.current) setSelectedVersion(version)
  }

  async function confirmVersionRestore() {
    if (!selectedVersion || mutationBusy.current) return
    const target = selectedVersion
    const sequence = ++mutationSequence.current
    const session = profileSession.current
    mutationController.current?.abort()
    const controller = new AbortController()
    mutationController.current = controller
    mutationBusy.current = true
    setRestoringVersionId(target.id)
    try {
      const detail = await getProfileVersion(profileId, target.id, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || mutationSequence.current !== sequence || profileSession.current !== session) return
      const restoreWarnings: string[] = []
      const nextBindings = modelTypes.map((modelType): ProfileModelBinding => {
        const resourceId = detail.snapshot.modelResourceIds[modelType]
        if (!resourceId) return { modelType, source: 'default' }
        const option = modelOptions.find((item) => item.modelType === modelType && item.source === 'global' && item.id === resourceId)
        if (!option || !option.enabled || option.credentialStatus === 'missing') {
          const current = modelBindings.find((binding) => binding.modelType === modelType)
          const currentOption = current?.source === 'global' && current.resourceId
            ? modelOptions.find((item) => item.modelType === modelType && item.source === 'global'
              && item.id === current.resourceId && item.enabled && item.credentialStatus !== 'missing')
            : undefined
          const fallback: ProfileModelBinding = current && (current.source === 'default' || currentOption)
            ? current : { modelType, source: 'default' }
          const result = fallback.source === 'default' ? '已改为跟随系统默认' : '已保留当前模型设置'
          const name = option?.name || `历史模型 ${resourceId}`
          restoreWarnings.push(option?.credentialStatus === 'missing'
            ? `${name} 未配置凭据，${result}` : `${name} 当前不可用，${result}`)
          return fallback
        }
        if (option?.isDefault) return { modelType, source: 'default' }
        return { modelType, source: 'global', resourceId, name: option?.name ?? `历史模型 ${resourceId}`,
          enabled: option?.enabled ?? false, unavailableReason: option ? option.unavailableReason : '历史版本使用的模型当前不可用' }
      })
      stopPreview()
      setPreviewError('')
      setModelBindings(nextBindings)
      setModelsChanged(true)
      setTtsModelChanged(true)
      setVoiceChanged(true)
      modelsRevision.current += 1
      ttsModelRevision.current += 1
      voiceRevision.current += 1
      formRevision.current += 1
      promptRevision.current += 1
      const currentValues = form.getFieldsValue(true)
      form.setFieldsValue({
        name: detail.snapshot.name, relationMode: detail.snapshot.relationMode ?? currentValues.relationMode,
        userAddress: detail.snapshot.userAddress ?? currentValues.userAddress,
        personality: detail.snapshot.personality ?? currentValues.personality,
        systemPrompt: detail.snapshot.systemPrompt, ttsVoiceId: detail.snapshot.ttsVoiceId ?? '',
        companionCues: detail.snapshot.companionCues ?? currentValues.companionCues,
        screenExpressionEnabled: detail.snapshot.screenExpressionEnabled ?? currentValues.screenExpressionEnabled,
        cameraPreferenceEnabled: detail.snapshot.cameraPreferenceEnabled ?? currentValues.cameraPreferenceEnabled,
      })
      setFormChanged(true)
      setModelSaveError('')
      const ttsBinding = nextBindings.find((binding) => binding.modelType === 'TTS')
      void loadNativeVoices(ttsResourceId(ttsBinding, modelOptions), detail.snapshot.ttsVoiceId ?? '')
      setSelectedVersion(null)
      if (restoreWarnings.length) messageApi.warning(restoreWarnings.join('；'))
      messageApi.success(`版本 ${detail.versionNo} 已恢复到草稿`)
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && mutationSequence.current === sequence && profileSession.current === session) {
        messageApi.error(reason instanceof Error ? reason.message : '版本恢复失败')
      }
    } finally {
      if (mounted.current && mutationSequence.current === sequence && profileSession.current === session) {
        mutationBusy.current = false
        setRestoringVersionId('')
      }
    }
  }

  function cancelVersionRestore() {
    mutationSequence.current += 1
    mutationController.current?.abort()
    mutationBusy.current = false
    setRestoringVersionId('')
    setSelectedVersion(null)
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

  function validateVoice(value: string | undefined) {
    if (!ttsModelChanged && !voiceChanged) return Promise.resolve()
    if (voiceLoadStatus === 'loading') return Promise.reject(new Error('声音列表加载中，请稍后保存'))
    if (voiceLoadStatus === 'failed') return Promise.reject(new Error('声音列表加载失败，请重试'))
    if (!value && !ttsModelChanged) return Promise.resolve()
    return !voices.length || voices.some((voice) => voice.id === value)
      ? Promise.resolve()
      : Promise.reject(new Error('请选择当前 TTS 模型的声音'))
  }

  const tabItems = [
    { key: 'basics', label: '角色设定', forceRender: true, children: <ProfileBasicsTab saving={saving} restoring={restoring} onRestorePrompt={openRestore} /> },
    { key: 'models', label: 'AI 模型', forceRender: true, children: <ProfileModelsTab modelBindings={modelBindings} modelOptions={modelOptions} modelSaveError={modelSaveError} onChangeModels={changeModels} /> },
    { key: 'voice', label: '声音与情绪', forceRender: true, children: <ProfileVoiceTab languageOptions={languageOptions} voiceLanguage={voiceLanguage}
      voices={voices} filteredVoices={filteredVoices} selectedVoice={selectedVoice} voiceLoadStatus={voiceLoadStatus}
      voiceError={voiceError} previewError={previewError} validateVoice={validateVoice}
      onLanguageChange={(language) => {
        setVoiceLanguage(language)
        const currentVoiceId = form.getFieldValue('ttsVoiceId')
        const currentVoice = voices.find((voice) => voice.id === currentVoiceId)
        if (language && currentVoice && !voiceLanguages(currentVoice.languages).includes(language)) {
          voiceRevision.current += 1
          setVoiceChanged(true)
          stopPreview()
          setPreviewError('')
          formRevision.current += 1
          form.setFieldValue('ttsVoiceId', '')
        }
      }}
      onVoiceChange={() => { voiceRevision.current += 1; setVoiceChanged(true); stopPreview(); setPreviewError('') }}
      onPreviewVoice={(voice) => void previewVoice(voice)} /> },
    { key: 'capabilities', label: '设备能力', forceRender: true, children: <ProfileCapabilitiesTab /> },
    { key: 'versions', label: '版本记录', forceRender: true, children: <ProfileVersionsTab versions={versions}
      hasMore={versionPage * versionPageSize < versionTotal} loadingMore={loadingMore} onLoadMore={() => void loadMoreVersions()}
      onRestore={openVersionRestore} restoringVersionId={restoringVersionId} /> },
  ]

  return (
    <PageContainer className="profile-editor-page" title={<h1 className="page-container-title">编辑陪伴角色</h1>}
      onBack={() => navigate('/profiles')}
      extra={profile ? <Button aria-label="保存角色" type="primary" icon={<SaveOutlined />} loading={saving}
        disabled={saving || restoring || Boolean(restoringVersionId)} onClick={() => form.submit()}>保存角色</Button> : undefined}>
      {messageContext}
      {error && <Alert type="error" showIcon message={error} action={<Button size="small" onClick={() => void load()}>重试</Button>} />}
      <Spin spinning={loading}>
        {profile && <Form form={form} layout="vertical" onFinish={(values) => void save(values)} onValuesChange={(changedValues) => {
          formRevision.current += 1
          if (Object.prototype.hasOwnProperty.call(changedValues, 'systemPrompt')) promptRevision.current += 1
          setFormChanged(true)
        }}>
          <Typography.Paragraph>在页签间切换不会丢失尚未保存的草稿。</Typography.Paragraph>
          <Tabs activeKey={activeTab} destroyOnHidden={false} items={tabItems} onChange={(tab) => {
            const next = new URLSearchParams(searchParams)
            next.set('tab', tab)
            setSearchParams(next)
          }} />
        </Form>}
      </Spin>
      <Modal title="恢复初始提示词" open={restoreOpen} confirmLoading={restoring} okText="确认恢复" cancelText="取消"
        okButtonProps={{ disabled: saving || restoring }} onCancel={() => setRestoreOpen(false)} onOk={() => void confirmRestore()}>
        <Typography.Paragraph>只恢复完整提示词，不会覆盖名称、关系、声音、设备绑定和历史记录。</Typography.Paragraph>
      </Modal>
      <Modal title={selectedVersion ? `恢复版本 ${selectedVersion.versionNo}` : '恢复历史版本'} open={Boolean(selectedVersion)}
        confirmLoading={Boolean(restoringVersionId)} okText="确认恢复" cancelText="取消"
        closable={!restoringVersionId} maskClosable={!restoringVersionId}
        cancelButtonProps={{ disabled: Boolean(restoringVersionId) }}
        okButtonProps={{ disabled: saving || restoring || Boolean(restoringVersionId) }}
        onCancel={cancelVersionRestore} onOk={() => void confirmVersionRestore()}>
        <Typography.Paragraph>恢复后会替换当前未保存草稿，服务端配置要到保存角色后才会更新。</Typography.Paragraph>
      </Modal>
    </PageContainer>
  )
}
