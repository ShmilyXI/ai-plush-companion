import {
  CameraOutlined,
  CheckCircleFilled,
  DesktopOutlined,
  DisconnectOutlined,
  PlusOutlined,
  RightOutlined,
} from '@ant-design/icons'
import { Alert, Button, Card, Empty, Space, Spin, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import { bindDevice, listDevices, type BindDeviceInput, type CompanionDevice } from '../../api/devices'
import { BindDeviceModal } from './BindDeviceModal'

function deviceName(device: CompanionDevice) {
  return device.alias?.trim() || '未命名设备'
}

function OnlineStatus({ online }: { online: boolean }) {
  return online
    ? <Tag icon={<CheckCircleFilled />} color="success">在线</Tag>
    : <Tag icon={<DisconnectOutlined />}>离线</Tag>
}

export function DeviceListPage() {
  const [devices, setDevices] = useState<CompanionDevice[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [bindOpen, setBindOpen] = useState(false)
  const [binding, setBinding] = useState(false)
  const [messageApi, messageContext] = message.useMessage()
  const mounted = useRef(false)
  const listRequest = useRef(0)
  const listController = useRef<AbortController | null>(null)
  const bindRequest = useRef(0)
  const bindController = useRef<AbortController | null>(null)

  const load = useCallback(async () => {
    const request = ++listRequest.current
    listController.current?.abort()
    const controller = new AbortController()
    listController.current = controller
    setLoading(true)
    setError('')
    try {
      const nextDevices = await listDevices({ signal: controller.signal })
      if (mounted.current && !controller.signal.aborted && listRequest.current === request) setDevices(nextDevices)
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && listRequest.current === request) {
        setError(reason instanceof Error ? reason.message : '设备列表加载失败')
      }
    } finally {
      if (mounted.current && !controller.signal.aborted && listRequest.current === request) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void load()
    const timer = window.setInterval(() => void load(), 30_000)
    return () => {
      window.clearInterval(timer)
      mounted.current = false
      listRequest.current += 1
      bindRequest.current += 1
      listController.current?.abort()
      bindController.current?.abort()
    }
  }, [load])

  async function handleBind(input: BindDeviceInput) {
    const request = ++bindRequest.current
    bindController.current?.abort()
    const controller = new AbortController()
    bindController.current = controller
    setBinding(true)
    try {
      await bindDevice(input, { signal: controller.signal })
      if (!mounted.current || controller.signal.aborted || bindRequest.current !== request) return
      setBindOpen(false)
      messageApi.success('设备已绑定')
      await load()
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && bindRequest.current === request) {
        messageApi.error(reason instanceof Error ? reason.message : '绑定失败')
      }
      throw reason
    } finally {
      if (mounted.current && bindRequest.current === request) setBinding(false)
    }
  }

  const columns: ColumnsType<CompanionDevice> = [
    {
      title: '设备',
      key: 'device',
      render: (_, device) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>{deviceName(device)}</Typography.Text>
          <Typography.Text type="secondary" className="device-mac">{device.macAddress}</Typography.Text>
        </Space>
      ),
    },
    { title: '状态', dataIndex: 'online', width: 110, render: (online: boolean) => <OnlineStatus online={online} /> },
    {
      title: '设备能力',
      key: 'capabilities',
      render: (_, device) => (
        <Space wrap>
          {device.hasDisplay && <Tag icon={<DesktopOutlined />}>屏幕</Tag>}
          {device.hasCamera && <Tag icon={<CameraOutlined />}>摄像头</Tag>}
          {!device.hasDisplay && !device.hasCamera && <Typography.Text type="secondary">基础语音</Typography.Text>}
        </Space>
      ),
    },
    { title: '版本', dataIndex: 'appVersion', width: 120, render: (version: string | null) => version || '未知' },
    {
      title: '',
      key: 'action',
      width: 80,
      align: 'right',
      render: (_, device) => <Link aria-label={`管理${deviceName(device)}`} to={`/devices/${encodeURIComponent(device.id)}`}><RightOutlined /></Link>,
    },
  ]

  return (
    <section className="console-page device-list-page">
      {messageContext}
      <div className="page-heading">
        <div>
          <Typography.Title level={1}>我的设备</Typography.Title>
          <Typography.Paragraph>查看在线状态，管理陪伴角色和设备设置。</Typography.Paragraph>
        </div>
        <Button aria-label="绑定设备" type="primary" icon={<PlusOutlined />} onClick={() => setBindOpen(true)}>绑定设备</Button>
      </div>
      {error && <Alert type="error" showIcon message={error} action={<Button size="small" onClick={() => void load()}>重试</Button>} />}
      <Card className="surface-card device-table-card" styles={{ body: { padding: 0 } }}>
        <Spin spinning={loading}>
          <div className="safe-table-scroll">
            <Table
              rowKey="id"
              columns={columns}
              dataSource={devices}
              pagination={false}
              locale={{ emptyText: <Empty description="还没有绑定设备" /> }}
            />
          </div>
        </Spin>
      </Card>
      <BindDeviceModal open={bindOpen} loading={binding} onCancel={() => setBindOpen(false)} onSubmit={handleBind} />
    </section>
  )
}
