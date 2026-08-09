import { Alert, Button, Card, Col, Form, Input, InputNumber, message, Row, Select, Space, Tag, Typography } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'

import { getSystemSettings, listTimbres, saveSystemSettings, type HealthStatus, type SystemSettingOption, type SystemSettings, type SystemSettingsInput } from '../../api/admin'
import { AdminPage } from './AdminPage'
import { adminErrorMessage } from './adminErrors'

const MODEL_FIELDS: Array<{ name: keyof SystemSettingsInput; label: string; type: string; optional?: boolean }> = [
  { name: 'defaultLlmModelId', label: '默认 LLM', type: 'LLM' },
  { name: 'defaultVllmModelId', label: '默认视觉模型', type: 'VLLM', optional: true },
  { name: 'defaultTtsModelId', label: '默认 TTS', type: 'TTS' },
  { name: 'defaultAsrModelId', label: '默认 ASR', type: 'ASR' },
  { name: 'defaultVadModelId', label: '默认 VAD', type: 'VAD' },
  { name: 'defaultMemoryModelId', label: '默认记忆模型', type: 'Memory', optional: true },
]

const healthLabels: Record<HealthStatus, { text: string; color: string }> = {
  available: { text: '可用', color: 'green' },
  unavailable: { text: '不可用', color: 'red' },
  unknown: { text: '未检测', color: 'default' },
}

function endpointRule(protocols: string[]) {
  return {
    validator(_: unknown, value: string) {
      try {
        const parsed = new URL(value)
        return protocols.includes(parsed.protocol) ? Promise.resolve() : Promise.reject(new Error('地址格式不正确'))
      } catch {
        return Promise.reject(new Error('地址格式不正确'))
      }
    },
  }
}

function modelOptions(settings: SystemSettings, type: string) {
  return (settings.modelOptions[type] || []).map((item) => ({ value: item.id, label: item.name }))
}

export function SystemSettingsPage() {
  const [form] = Form.useForm<SystemSettingsInput>()
  const [settings, setSettings] = useState<SystemSettings | null>(null)
  const [voices, setVoices] = useState<SystemSettingOption[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [saveError, setSaveError] = useState('')
  const mounted = useRef(true)
  const voiceController = useRef<AbortController | null>(null)
  const voiceSequence = useRef(0)

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true)
    setLoadError('')
    try {
      const result = await getSystemSettings(signal ? { signal } : undefined)
      if (!mounted.current || signal?.aborted) return
      setSettings(result)
      setVoices(result.voices)
      form.setFieldsValue(result)
    } catch (reason) {
      if (!signal?.aborted && mounted.current) setLoadError(adminErrorMessage(reason, '系统设置加载失败'))
    } finally {
      if (!signal?.aborted && mounted.current) setLoading(false)
    }
  }, [form])

  useEffect(() => {
    mounted.current = true
    const controller = new AbortController()
    void load(controller.signal)
    return () => {
      mounted.current = false
      controller.abort()
      voiceController.current?.abort()
    }
  }, [load])

  async function changeTtsModel(modelId: string) {
    form.setFieldValue('defaultTtsVoiceId', undefined)
    voiceController.current?.abort()
    const controller = new AbortController()
    const sequence = ++voiceSequence.current
    voiceController.current = controller
    try {
      const result = await listTimbres(modelId, '', 1, 100, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || sequence !== voiceSequence.current) return
      setVoices(result.list.map((item) => ({ id: item.id, name: item.name, type: 'TTS_VOICE' })))
    } catch (reason) {
      if (!controller.signal.aborted && mounted.current && sequence === voiceSequence.current) {
        setSaveError(adminErrorMessage(reason, '音色加载失败'))
      }
    }
  }

  async function submit(values: SystemSettingsInput) {
    setSaving(true)
    setSaveError('')
    try {
      const refreshed = await saveSystemSettings(values)
      if (!mounted.current) return
      setSettings(refreshed)
      setVoices(refreshed.voices)
      form.setFieldsValue(refreshed)
      message.success(refreshed.restartRequired ? '设置已保存，监听配置需重启后生效' : '设置已保存')
    } catch (reason) {
      if (mounted.current) setSaveError(adminErrorMessage(reason, '保存失败'))
    } finally {
      if (mounted.current) setSaving(false)
    }
  }

  const error = saveError || loadError
  return <AdminPage title="系统设置" loading={loading} error={error}>
    {!settings ? <Button aria-label="重试" onClick={() => void load()}>重试</Button> : <Form form={form} layout="vertical" onFinish={submit} requiredMark="optional">
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {settings.restartRequired && <Alert showIcon type="warning" message={`设置已保存，${settings.restartServices.join('、')} 服务需重启后生效`} />}

        <Card size="small" title="设备连接">
          <Row gutter={[16, 0]}>
            <Col xs={24} lg={12}><Form.Item name="publicWebsocketUrl" label="公开 WebSocket 地址" rules={[{ required: true, message: '请输入公开 WebSocket 地址' }, endpointRule(['ws:', 'wss:'])]}><Input placeholder="wss://example.com/ws" /></Form.Item></Col>
            <Col xs={24} lg={12}><Form.Item name="publicOtaUrl" label="公开 OTA 地址" rules={[{ required: true, message: '请输入公开 OTA 地址' }, endpointRule(['http:', 'https:'])]}><Input placeholder="https://example.com/ota/" /></Form.Item></Col>
          </Row>
        </Card>

        <Card size="small" title="本地服务">
          <Alert showIcon type="info" style={{ marginBottom: 16 }} message="监听地址和端口保存后，需要重启对应服务才会生效。后台不会启动或停止系统进程。" />
          <Row gutter={[16, 0]}>
            <Col xs={24} md={12} lg={8}><Form.Item name="xiaozhiListenHost" label="小智监听地址" rules={[{ required: true, message: '请输入监听地址' }]}><Input /></Form.Item></Col>
            <Col xs={24} md={12} lg={4}><Form.Item name="xiaozhiListenPort" label="小智端口" rules={[{ required: true, message: '请输入端口' }]}><InputNumber min={1} max={65535} style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={24} lg={12}><ServiceStatus label="小智服务" health={settings.health.xiaozhi} /></Col>
            <Col xs={24} md={12} lg={8}><Form.Item name="otaListenHost" label="OTA 监听地址" rules={[{ required: true, message: '请输入监听地址' }]}><Input /></Form.Item></Col>
            <Col xs={24} md={12} lg={4}><Form.Item name="otaListenPort" label="OTA 端口" rules={[{ required: true, message: '请输入端口' }]}><InputNumber min={1} max={65535} style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={24} lg={12}><ServiceStatus label="OTA 服务" health={settings.health.ota} /></Col>
          </Row>
        </Card>

        <Card size="small" title="默认 AI 资源">
          <Alert showIcon type="info" style={{ marginBottom: 16 }} message="启用表示资源可被选择，不代表设备正在使用。" />
          <Row gutter={[16, 0]}>
            {MODEL_FIELDS.map((field) => <Col xs={24} md={12} key={field.name}>
              <Form.Item name={field.name} label={field.label} rules={field.optional ? undefined : [{ required: true, message: `请选择${field.label}` }]}>
                <Select allowClear={field.optional} showSearch optionFilterProp="label" options={modelOptions(settings, field.type)} onChange={field.type === 'TTS' ? (value) => void changeTtsModel(value) : undefined} />
              </Form.Item>
            </Col>)}
            <Col xs={24} md={12}><Form.Item name="defaultTtsVoiceId" label="默认音色" rules={[{ required: true, message: '请选择默认音色' }]}><Select showSearch optionFilterProp="label" options={voices.map((item) => ({ value: item.id, label: item.name }))} /></Form.Item></Col>
          </Row>
        </Card>

        <Button type="primary" htmlType="submit" loading={saving}>保存设置</Button>
      </Space>
    </Form>}
  </AdminPage>
}

function ServiceStatus({ label, health }: { label: string; health: SystemSettings['health']['xiaozhi'] }) {
  const status = healthLabels[health.status]
  return <Space direction="vertical" size={2} style={{ paddingTop: 4, paddingBottom: 18 }}>
    <Space><Typography.Text strong>{label}</Typography.Text><Tag color={status.color}>{status.text}</Tag></Space>
    <Typography.Text type="secondary">{health.address}</Typography.Text>
    <Typography.Text type="secondary">检测时间 {new Date(health.checkedAt).toLocaleString()}</Typography.Text>
  </Space>
}
