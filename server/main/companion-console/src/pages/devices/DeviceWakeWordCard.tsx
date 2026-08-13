import { Alert, Button, Card, Form, Input, Skeleton, Space, Tag, Typography } from 'antd'
import { useEffect, useMemo, useState } from 'react'

import {
  getDeviceWakeWord,
  retryDeviceWakeWord,
  updateDeviceWakeWord,
  type DeviceWakeWordState,
  type WakeWordStatus,
} from '../../api/devices'

const statusText: Record<WakeWordStatus, string> = {
  IDLE: '未配置',
  GENERATING: '正在生成资源',
  WAITING_DEVICE: '等待设备上线',
  DOWNLOADING: '设备正在下载',
  WAITING_REBOOT: '等待设备重启确认',
  ACTIVE: '已生效',
  FAILED: '更新失败',
}

const pendingStatuses = new Set<WakeWordStatus>(['GENERATING', 'WAITING_DEVICE', 'DOWNLOADING', 'WAITING_REBOOT'])
const validWord = /^[\u3400-\u4dbf\u4e00-\u9fff]{2,8}$/u

export function DeviceWakeWordCard({ deviceId }: { deviceId: string }) {
  const [state, setState] = useState<DeviceWakeWordState | null>(null)
  const [word, setWord] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const pending = Boolean(state && pendingStatuses.has(state.status))

  useEffect(() => {
    let active = true
    let timer: number | undefined
    const load = async () => {
      try {
        const next = await getDeviceWakeWord(deviceId)
        if (!active) return
        setState(next)
        setWord(next.desiredWord || next.activeWord || '')
        setError('')
        if (pendingStatuses.has(next.status)) timer = window.setTimeout(load, 5000)
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : '唤醒词状态加载失败')
      } finally {
        if (active) setLoading(false)
      }
    }
    void load()
    return () => {
      active = false
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [deviceId])

  const tagColor = useMemo(() => {
    if (!state) return 'default'
    if (state.status === 'ACTIVE') return 'success'
    if (state.status === 'FAILED') return 'error'
    if (pendingStatuses.has(state.status)) return 'processing'
    return 'default'
  }, [state])

  async function save() {
    const normalized = word.trim()
    if (!validWord.test(normalized)) {
      setError('只支持二到八个中文汉字')
      return
    }
    setSaving(true)
    setError('')
    try {
      setState(await updateDeviceWakeWord(deviceId, normalized))
      setWord(normalized)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '唤醒词更新失败')
    } finally {
      setSaving(false)
    }
  }

  async function retry() {
    setSaving(true)
    setError('')
    try {
      setState(await retryDeviceWakeWord(deviceId))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '重试失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card title="唤醒词" style={{ marginTop: 16 }}>
      {loading && !state ? <Skeleton active paragraph={{ rows: 2 }} /> : null}
      {error ? <Alert role="alert" type="error" showIcon message={error} style={{ marginBottom: 16 }} /> : null}
      {state && !state.supported ? (
        <Alert type="warning" showIcon message="当前设备暂不支持动态唤醒词" description={state.unsupportedReason || undefined} />
      ) : null}
      {state?.supported ? (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space wrap>
            <Typography.Text>当前生效</Typography.Text>
            <Typography.Text strong>{state.activeWord || '未配置'}</Typography.Text>
            <Tag color={tagColor}>{statusText[state.status]}</Tag>
          </Space>
          {state.status === 'FAILED' && state.lastErrorMessage ? <Alert type="error" showIcon message={state.lastErrorMessage} /> : null}
          <Form layout="vertical" onFinish={save}>
            <Form.Item label="新唤醒词" style={{ marginBottom: 12 }}>
              <Input aria-label="新唤醒词" value={word} maxLength={8} disabled={pending || saving} onChange={(event) => setWord(event.target.value)} />
            </Form.Item>
            <Space wrap>
              <Button type="primary" htmlType="submit" loading={saving} disabled={pending}>保存并下发</Button>
              {state.status === 'FAILED' ? <Button aria-label="重试" loading={saving} onClick={() => void retry()}>重试</Button> : null}
            </Space>
          </Form>
        </Space>
      ) : null}
    </Card>
  )
}
