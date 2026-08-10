import { CheckCircleFilled, ClockCircleOutlined, DesktopOutlined, DisconnectOutlined, IdcardOutlined, RobotOutlined } from '@ant-design/icons'
import { PageContainer, ProCard, StatisticCard } from '@ant-design/pro-components'
import { Alert, Button, Empty, List, Skeleton, Space, Tag, Typography } from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import { listRecentSessions, type RecentSession } from '../api/dashboard'
import { listDevices, listProfiles, type CompanionDevice, type CompanionProfileSummary } from '../api/devices'
import { getSubscription, type CompanionSubscription } from '../api/subscription'
import { routeTitle } from '../app/navigation'

export function DashboardPage() {
  const [devices, setDevices] = useState<CompanionDevice[] | null>(null)
  const [profiles, setProfiles] = useState<CompanionProfileSummary[] | null>(null)
  const [subscription, setSubscription] = useState<CompanionSubscription | null>(null)
  const [recentSessions, setRecentSessions] = useState<Array<RecentSession & { profileName: string }> | null>(null)
  const [deviceLoading, setDeviceLoading] = useState(true)
  const [profileLoading, setProfileLoading] = useState(true)
  const [subscriptionLoading, setSubscriptionLoading] = useState(true)
  const [deviceError, setDeviceError] = useState('')
  const [profileError, setProfileError] = useState('')
  const [subscriptionError, setSubscriptionError] = useState('')
  const [recentError, setRecentError] = useState('')
  const [deviceReload, setDeviceReload] = useState(0)
  const [profileReload, setProfileReload] = useState(0)
  const deviceRequest = useRef(0)
  const profileRequest = useRef(0)

  useEffect(() => {
    const request = ++deviceRequest.current
    const controller = new AbortController()
    setDeviceLoading(true)
    setDeviceError('')
    void listDevices({ signal: controller.signal }).then((nextDevices) => {
      if (!controller.signal.aborted && request === deviceRequest.current) setDevices(nextDevices)
    }).catch((reason) => {
      if (!controller.signal.aborted && request === deviceRequest.current) {
        setDevices(null)
        setDeviceError(reason instanceof Error ? reason.message : '设备统计加载失败')
      }
    }).finally(() => {
      if (!controller.signal.aborted && request === deviceRequest.current) setDeviceLoading(false)
    })
    return () => controller.abort()
  }, [deviceReload])

  useEffect(() => {
    const request = ++profileRequest.current
    const controller = new AbortController()
    setProfileLoading(true)
    setProfileError('')
    void listProfiles({ signal: controller.signal }).then((profiles) => {
      if (!controller.signal.aborted && request === profileRequest.current) setProfiles(profiles)
    }).catch((reason) => {
      if (!controller.signal.aborted && request === profileRequest.current) {
        setProfiles(null)
        setProfileError(reason instanceof Error ? reason.message : '角色统计加载失败')
      }
    }).finally(() => {
      if (!controller.signal.aborted && request === profileRequest.current) setProfileLoading(false)
    })
    return () => controller.abort()
  }, [profileReload])

  useEffect(() => {
    const controller = new AbortController()
    setSubscriptionLoading(true)
    setSubscriptionError('')
    void getSubscription({ signal: controller.signal }).then((value) => {
      if (!controller.signal.aborted) setSubscription(value)
    }).catch((reason) => {
      if (!controller.signal.aborted) {
        setSubscription(null)
        setSubscriptionError(reason instanceof Error ? reason.message : '订阅状态加载失败')
      }
    }).finally(() => {
      if (!controller.signal.aborted) setSubscriptionLoading(false)
    })
    return () => controller.abort()
  }, [])

  const activeProfileIds = useMemo(() => Array.from(new Set(
    (devices ?? []).map((device) => device.activeProfileId).filter((id): id is string => Boolean(id)),
  )), [devices])

  useEffect(() => {
    if (!devices || !profiles) return
    if (!activeProfileIds.length) {
      setRecentSessions([])
      setRecentError('')
      return
    }
    const controller = new AbortController()
    setRecentSessions(null)
    setRecentError('')
    void Promise.all(activeProfileIds.map(async (profileId) => {
      const profileName = profiles.find((profile) => profile.id === profileId)?.name || '未知角色'
      const sessions = await listRecentSessions(profileId, { signal: controller.signal })
      return sessions.map((session) => ({ ...session, profileName }))
    })).then((groups) => {
      if (!controller.signal.aborted) {
        setRecentSessions(groups.flat().sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt)).slice(0, 3))
      }
    }).catch((reason) => {
      if (!controller.signal.aborted) {
        setRecentSessions(null)
        setRecentError(reason instanceof Error ? reason.message : '最近对话加载失败')
      }
    })
    return () => controller.abort()
  }, [activeProfileIds, devices, profiles])

  const onlineCount = devices?.filter((device) => device.online).length ?? null
  const profileNames = new Map((profiles ?? []).map((profile) => [profile.id, profile.name]))

  return (
    <PageContainer title={<h1 className="page-container-title">{routeTitle('dashboard')}</h1>} subTitle="查看设备、角色和最近对话">
      <StatisticCard.Group direction="row" wrap>
        <StatisticCard colSpan={{ xs: 12, lg: 6 }} loading={deviceLoading} statistic={{ title: '在线设备', value: onlineCount ?? '不可用', icon: <CheckCircleFilled /> }} />
        <StatisticCard colSpan={{ xs: 12, lg: 6 }} loading={deviceLoading} statistic={{ title: '全部设备', value: devices?.length ?? '不可用', icon: <DesktopOutlined /> }} />
        <StatisticCard colSpan={{ xs: 12, lg: 6 }} loading={profileLoading} statistic={{ title: '陪伴角色', value: profiles?.length ?? '不可用', icon: <RobotOutlined /> }} />
        <StatisticCard
          colSpan={{ xs: 12, lg: 6 }}
          loading={subscriptionLoading}
          extra={<Link to="/subscription">查看权益</Link>}
          statistic={{
            title: '订阅状态',
            value: subscription?.planName ?? '不可用',
            icon: <IdcardOutlined />,
            description: subscription
              ? subscription.expiresAt
                ? `有效期至 ${new Date(subscription.expiresAt).toLocaleDateString('zh-CN')}`
                : '长期有效'
              : undefined,
          }}
        />
      </StatisticCard.Group>
      {profileError && <Alert className="dashboard-alert" type="warning" showIcon message={profileError} action={<Button size="small" onClick={() => setProfileReload((value) => value + 1)}>重试角色统计</Button>} />}
      {subscriptionError && <Alert className="dashboard-alert" type="warning" showIcon message={subscriptionError} />}
      <ProCard split="vertical" gutter={16} style={{ marginTop: 16 }} wrap>
        <ProCard title="最近设备" extra={<Link to="/devices">管理设备</Link>} colSpan={{ xs: 24, lg: 12 }}>
          {deviceLoading ? <Skeleton active paragraph={{ rows: 2 }} /> : deviceError ? (
            <Alert type="error" showIcon message={deviceError} action={<Button size="small" onClick={() => setDeviceReload((value) => value + 1)}>重试设备</Button>} />
          ) : devices?.length === 0 ? <Empty description="还没有绑定设备" /> : devices?.slice(0, 4).map((device) => (
            <Link className="dashboard-device-row" to={`/devices/${encodeURIComponent(device.id)}`} key={device.id}>
              <Space direction="vertical" size={0}>
                <Typography.Text strong>{device.alias?.trim() || '未命名设备'}</Typography.Text>
                <Typography.Text type="secondary">{device.macAddress}</Typography.Text>
                <Typography.Text type="secondary"><span>当前角色</span> <strong>{device.activeProfileId ? profileNames.get(device.activeProfileId) || '未知角色' : '未设置'}</strong></Typography.Text>
              </Space>
              {device.online
                ? <Tag icon={<CheckCircleFilled />} color="success">在线</Tag>
                : <Tag icon={<DisconnectOutlined />}>离线</Tag>}
            </Link>
          ))}
        </ProCard>
        <ProCard title="最近会话" colSpan={{ xs: 24, lg: 12 }}>
          {recentError ? <Alert type="warning" showIcon message={recentError} /> : recentSessions === null ? <Skeleton active paragraph={{ rows: 2 }} /> : (
            <List dataSource={recentSessions} locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有对话记录" /> }} renderItem={(session) => (
              <List.Item>
                <List.Item.Meta title={session.title} description={<Space split="·"><span>{session.profileName}</span><span><ClockCircleOutlined /> {new Date(session.createdAt).toLocaleString('zh-CN')}</span></Space>} />
              </List.Item>
            )} />
          )}
        </ProCard>
      </ProCard>
    </PageContainer>
  )
}
