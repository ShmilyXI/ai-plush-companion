import {
  ArrowLeftOutlined,
  CameraOutlined,
  CheckCircleFilled,
  DeleteOutlined,
  DisconnectOutlined,
  EditOutlined,
} from '@ant-design/icons'
import { PageContainer, ProDescriptions } from '@ant-design/pro-components'
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Result,
  Select,
  Skeleton,
  Slider,
  Space,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import {
  getDevice,
  listProfiles,
  sendDeviceCommand,
  switchDeviceProfile,
  unbindDevice,
  updateDevice,
  type CompanionDevice,
  type CompanionProfileSummary,
} from '../../api/devices'
import { ApiError } from '../../api/http'
import { EffectiveModelSummary } from '../../components/EffectiveModelSummary'
import { DeviceDebugLogPanel } from './DeviceDebugLogPanel'

function readableCommandError(reason: unknown, setting: string, heartbeatOnline: boolean) {
  if (reason instanceof ApiError && reason.code === 10205) {
    return heartbeatOnline
      ? `设备心跳在线，但实时控制通道不可用，${setting}没有更改`
      : `设备离线，${setting}没有更改`
  }
  if (reason instanceof ApiError && reason.code === 10206) return `设备未能应用${setting}`
  return reason instanceof Error ? reason.message : `${setting}更新失败`
}

function isFormValidationError(reason: unknown) {
  return Boolean(reason && typeof reason === 'object' && 'errorFields' in reason)
}

export function DeviceDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const [device, setDevice] = useState<CompanionDevice | null>(null)
  const [profiles, setProfiles] = useState<CompanionProfileSummary[]>([])
  const [profileError, setProfileError] = useState('')
  const [profileLoading, setProfileLoading] = useState(true)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [reload, setReload] = useState(0)
  const [renameOpen, setRenameOpen] = useState(false)
  const [renaming, setRenaming] = useState(false)
  const [switching, setSwitching] = useState(false)
  const [unbinding, setUnbinding] = useState(false)
  const [volumeDraft, setVolumeDraft] = useState(50)
  const [brightnessDraft, setBrightnessDraft] = useState(50)
  const [commandLoading, setCommandLoading] = useState<'volume' | 'brightness' | null>(null)
  const [commandError, setCommandError] = useState('')
  const [renameForm] = Form.useForm<{ alias: string }>()
  const [messageApi, messageContext] = message.useMessage()
  const mounted = useRef(false)
  const currentDevice = useRef<CompanionDevice | null>(null)
  const currentRouteId = useRef(id)
  const routeVersion = useRef(0)
  const loadedVersion = useRef(0)
  const deviceRequest = useRef(0)
  const mutationControllers = useRef(new Set<AbortController>())
  const renameToken = useRef(0)
  const switchToken = useRef(0)
  const commandToken = useRef(0)
  const unbindToken = useRef(0)

  useEffect(() => {
    const controllers = mutationControllers.current
    mounted.current = true
    return () => {
      mounted.current = false
      controllers.forEach((controller) => controller.abort())
      controllers.clear()
    }
  }, [])

  useEffect(() => {
    const version = ++routeVersion.current
    const initialDeviceRequest = ++deviceRequest.current
    const controller = new AbortController()
    const refreshControllers = new Set<AbortController>()
    currentRouteId.current = id
    loadedVersion.current = 0
    mutationControllers.current.forEach((mutationController) => mutationController.abort())
    mutationControllers.current.clear()
    renameToken.current += 1
    switchToken.current += 1
    commandToken.current += 1
    unbindToken.current += 1
    currentDevice.current = null
    setDevice(null)
    setProfiles([])
    setLoadError('')
    setProfileError('')
    setLoading(true)
    setProfileLoading(true)
    setRenameOpen(false)
    setRenaming(false)
    setSwitching(false)
    setUnbinding(false)
    setCommandLoading(null)
    setCommandError('')

    void getDevice(id, { signal: controller.signal }).then((nextDevice) => {
      if (!controller.signal.aborted && mounted.current && routeVersion.current === version && deviceRequest.current === initialDeviceRequest) {
        loadedVersion.current = version
        currentDevice.current = nextDevice
        setDevice(nextDevice)
      }
    }).catch((reason) => {
      if (!controller.signal.aborted && mounted.current && routeVersion.current === version && deviceRequest.current === initialDeviceRequest) {
        setLoadError(reason instanceof Error ? reason.message : '设备信息加载失败')
      }
    }).finally(() => {
      if (!controller.signal.aborted && mounted.current && routeVersion.current === version && deviceRequest.current === initialDeviceRequest) setLoading(false)
    })

    void listProfiles({ signal: controller.signal }).then((nextProfiles) => {
      if (!controller.signal.aborted && mounted.current && routeVersion.current === version) setProfiles(nextProfiles)
    }).catch((reason) => {
      if (!controller.signal.aborted && mounted.current && routeVersion.current === version) {
        setProfileError(reason instanceof Error ? reason.message : '陪伴角色加载失败')
      }
    }).finally(() => {
      if (!controller.signal.aborted && mounted.current && routeVersion.current === version) setProfileLoading(false)
    })

    const timer = window.setInterval(() => {
      const request = ++deviceRequest.current
      const refreshController = new AbortController()
      refreshControllers.add(refreshController)
      void getDevice(id, { signal: refreshController.signal }).then((nextDevice) => {
        if (!refreshController.signal.aborted && mounted.current && routeVersion.current === version && deviceRequest.current === request) {
          loadedVersion.current = version
          currentDevice.current = nextDevice
          setDevice(nextDevice)
          setLoading(false)
        }
      }).catch((reason) => {
        if (!refreshController.signal.aborted
          && mounted.current
          && routeVersion.current === version
          && deviceRequest.current === request
          && currentDevice.current === null) {
          setLoadError(reason instanceof Error ? reason.message : '设备信息加载失败')
          setLoading(false)
        }
      }).finally(() => {
        refreshControllers.delete(refreshController)
      })
    }, 30_000)

    return () => {
      window.clearInterval(timer)
      controller.abort()
      refreshControllers.forEach((refreshController) => refreshController.abort())
      refreshControllers.clear()
    }
  }, [id, reload])

  const activeProfileName = useMemo(
    () => profiles.find((profile) => profile.id === device?.activeProfileId)?.name || '未设置',
    [device?.activeProfileId, profiles],
  )

  function currentContext(deviceId: string, version: number) {
    return mounted.current
      && currentRouteId.current === deviceId
      && routeVersion.current === version
      && loadedVersion.current === version
  }

  function mutationController() {
    const controller = new AbortController()
    mutationControllers.current.add(controller)
    return controller
  }

  async function rename() {
    let controller: AbortController | null = null
    let token = 0
    let deviceId = ''
    let version = 0
    try {
      const { alias } = await renameForm.validateFields()
      if (!device) return
      deviceId = device.id
      version = loadedVersion.current
      if (!currentContext(deviceId, version)) return
      token = ++renameToken.current
      controller = mutationController()
      setRenaming(true)
      await updateDevice(deviceId, { alias: alias.trim() }, { signal: controller.signal })
      if (currentContext(deviceId, version)) {
        setDevice((current) => current?.id === deviceId ? { ...current, alias: alias.trim() } : current)
        setRenameOpen(false)
        messageApi.success('设备名称已更新')
      }
    } catch (reason) {
      if (!isFormValidationError(reason) && controller && !controller.signal.aborted && currentContext(deviceId, version)) {
        messageApi.error(reason instanceof Error ? reason.message : '名称更新失败')
      }
    } finally {
      if (controller) mutationControllers.current.delete(controller)
      if (mounted.current && token > 0 && renameToken.current === token) setRenaming(false)
    }
  }

  async function switchProfile(profileId: string) {
    if (!device) return
    const deviceId = device.id
    const version = loadedVersion.current
    const token = ++switchToken.current
    const controller = mutationController()
    setSwitching(true)
    try {
      await switchDeviceProfile(deviceId, profileId, { signal: controller.signal })
      if (currentContext(deviceId, version)) {
        setDevice((current) => current?.id === deviceId ? { ...current, activeProfileId: profileId } : current)
        messageApi.success('陪伴角色已切换')
      }
    } catch (reason) {
      if (!controller.signal.aborted && currentContext(deviceId, version)) {
        messageApi.error(reason instanceof Error ? reason.message : '角色切换失败')
      }
    } finally {
      mutationControllers.current.delete(controller)
      if (mounted.current && switchToken.current === token) setSwitching(false)
    }
  }

  async function applyCommand(command: 'volume' | 'brightness') {
    if (!device) return
    const deviceId = device.id
    const version = loadedVersion.current
    const token = ++commandToken.current
    const controller = mutationController()
    const nextValue = command === 'volume' ? volumeDraft : brightnessDraft
    const setting = command === 'volume' ? '音量' : '亮度'
    setCommandError('')
    setCommandLoading(command)
    try {
      await sendDeviceCommand(deviceId, command, nextValue, { signal: controller.signal })
      if (currentContext(deviceId, version)) messageApi.success(`${setting}已更新`)
    } catch (reason) {
      if (!controller.signal.aborted && currentContext(deviceId, version)) {
        setCommandError(readableCommandError(reason, setting, currentDevice.current?.online === true))
      }
    } finally {
      mutationControllers.current.delete(controller)
      if (mounted.current && commandToken.current === token) setCommandLoading(null)
    }
  }

  async function unbind() {
    if (!device) return
    const deviceId = device.id
    const version = loadedVersion.current
    const token = ++unbindToken.current
    const controller = mutationController()
    setUnbinding(true)
    try {
      await unbindDevice(deviceId, { signal: controller.signal })
      if (currentContext(deviceId, version)) navigate('/devices', { replace: true })
    } catch (reason) {
      if (!controller.signal.aborted && currentContext(deviceId, version)) {
        messageApi.error(reason instanceof Error ? reason.message : '解绑失败')
      }
    } finally {
      mutationControllers.current.delete(controller)
      if (mounted.current && unbindToken.current === token) setUnbinding(false)
    }
  }

  function updateDebugLogEnabled(enabled: boolean) {
    deviceRequest.current += 1
    setDevice((current) => {
      if (current?.id !== device.id) return current
      const nextDevice = { ...current, debugLogEnabled: enabled }
      currentDevice.current = nextDevice
      return nextDevice
    })
  }

  if (loading) return <Card className="surface-card"><Skeleton active /></Card>
  if (loadError || !device) {
    return <Result status="error" title="设备信息无法加载" subTitle={loadError} extra={<Button onClick={() => setReload((value) => value + 1)}>重试</Button>} />
  }

  const displayName = device.alias?.trim() || '未命名设备'

  return (
    <PageContainer
      className="console-page device-detail-page"
      title={<h1 className="page-container-title">{displayName}</h1>}
      extra={<Button aria-label="修改名称" icon={<EditOutlined />} onClick={() => {
        renameForm.setFieldsValue({ alias: displayName })
        setRenameOpen(true)
      }}>修改名称</Button>}
    >
      {messageContext}
      <Link className="back-link" to="/devices"><ArrowLeftOutlined /> 返回设备列表</Link>

      <div className="detail-grid">
        <Card title="设备信息" className="surface-card">
          <ProDescriptions column={1} size="small">
            <ProDescriptions.Item label="设备状态">
              {device.online
                ? <Tag icon={<CheckCircleFilled />} color="success">在线</Tag>
                : <Tag icon={<DisconnectOutlined />}>离线</Tag>}
            </ProDescriptions.Item>
            <ProDescriptions.Item label="MAC 地址" copyable>{device.macAddress}</ProDescriptions.Item>
            <ProDescriptions.Item label="固件版本">{device.appVersion || '未知'}</ProDescriptions.Item>
            <ProDescriptions.Item label="当前角色">{activeProfileName}</ProDescriptions.Item>
            <ProDescriptions.Item label="设备能力">
              <Space wrap>
                <span>{`屏幕：${device.hasDisplay ? '支持' : '不支持'}`}</span>
                <span>{`摄像头：${device.hasCamera ? '支持' : '不支持'}`}</span>
              </Space>
            </ProDescriptions.Item>
            <ProDescriptions.Item label="设备编号">{device.id}</ProDescriptions.Item>
            <ProDescriptions.Item label="设备型号">{device.board || '未知'}</ProDescriptions.Item>
            <ProDescriptions.Item label="最后连接">{device.lastConnectedAt ? new Date(device.lastConnectedAt).toLocaleString('zh-CN') : '暂无记录'}</ProDescriptions.Item>
          </ProDescriptions>
        </Card>

        <Card title="陪伴角色" className="surface-card">
          <Typography.Paragraph type="secondary">当前角色 {activeProfileName}</Typography.Paragraph>
          {profileError && <Alert className="profile-alert" type="warning" showIcon message={profileError} />}
          <Select
            aria-label="陪伴角色"
            value={device.activeProfileId || undefined}
            loading={profileLoading || switching}
            disabled={profileLoading || switching || Boolean(profileError)}
            options={profiles.map((profile) => ({ label: profile.name, value: profile.id }))}
            onChange={(profileId) => void switchProfile(profileId)}
            placeholder="选择陪伴角色"
          />
          <Button
            aria-label="摄像头偏好"
            className="preference-button"
            icon={<CameraOutlined />}
            disabled={!device.hasCamera}
            onClick={() => navigate('/profiles')}
          >摄像头偏好</Button>
          {!device.hasCamera && <Typography.Paragraph className="capability-note" type="secondary">此设备没有摄像头</Typography.Paragraph>}
        </Card>

        <Card title="设备控制" className="surface-card control-card">
          <Typography.Paragraph type="secondary">这里设置的是待下发目标值，不代表设备当前状态。</Typography.Paragraph>
          {commandError && <Alert type="error" showIcon message={commandError} closable onClose={() => setCommandError('')} />}
          <div className="control-row">
            <div className="control-label"><Typography.Text strong>音量</Typography.Text><Typography.Text type="secondary">{volumeDraft}%</Typography.Text></div>
            <Slider ariaLabelForHandle="音量" value={volumeDraft} onChange={setVolumeDraft} disabled={commandLoading !== null} />
            <Button loading={commandLoading === 'volume'} disabled={commandLoading !== null} onClick={() => void applyCommand('volume')}>应用音量</Button>
          </div>
          <div className="control-row">
            <div className="control-label"><Typography.Text strong>屏幕亮度</Typography.Text><Typography.Text type="secondary">{brightnessDraft}%</Typography.Text></div>
            <Slider ariaLabelForHandle="屏幕亮度" value={brightnessDraft} onChange={setBrightnessDraft} disabled={!device.hasDisplay || commandLoading !== null} />
            <Button loading={commandLoading === 'brightness'} disabled={!device.hasDisplay || commandLoading !== null} onClick={() => void applyCommand('brightness')}>应用亮度</Button>
            {!device.hasDisplay && <Typography.Paragraph className="capability-note" type="secondary">此设备没有屏幕</Typography.Paragraph>}
          </div>
        </Card>

        <Card title="实际生效的 AI 模型" className="surface-card effective-model-card">
          <Typography.Paragraph type="secondary">这里显示设备当前角色解析后的配置，不把启用状态当成正在使用。</Typography.Paragraph>
          <EffectiveModelSummary models={device.effectiveModels ?? []} />
        </Card>

        <DeviceDebugLogPanel
          deviceId={device.id}
          enabled={device.debugLogEnabled}
          onEnabledChange={updateDebugLogEnabled}
        />

        <Card title="解绑设备" className="surface-card danger-card">
          <Typography.Paragraph type="secondary">解绑后，设备将从当前账号移除。</Typography.Paragraph>
          <Popconfirm
            title="确认解绑这台设备吗？"
            description="设备上的陪伴设置不会再与当前账号关联。"
            okText="确认解绑"
            cancelText="取消"
            okButtonProps={{ danger: true, loading: unbinding }}
            onConfirm={unbind}
          >
            <Button danger icon={<DeleteOutlined />}>解绑设备</Button>
          </Popconfirm>
        </Card>
      </div>

      <Modal title="修改设备名称" open={renameOpen} okText="保存名称" cancelText="取消" confirmLoading={renaming} onOk={rename} onCancel={() => setRenameOpen(false)}>
        <Form form={renameForm} layout="vertical">
          <Form.Item name="alias" label="设备名称" rules={[{ required: true, whitespace: true, message: '请输入设备名称' }, { max: 64, message: '设备名称不能超过 64 个字符' }]}>
            <Input maxLength={64} />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  )
}
