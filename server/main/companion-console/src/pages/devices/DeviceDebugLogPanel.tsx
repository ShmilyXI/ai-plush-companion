import {
  DeleteOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Badge,
  Button,
  Card,
  Empty,
  Space,
  Switch,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'

import {
  getDeviceDebugLogHistory,
  streamDeviceDebugLogs,
  type DebugLogCategory,
  type DebugLogEvent,
  type DebugLogLevel,
} from '../../api/deviceDebugLogs'
import { setDeviceDebugLogging } from '../../api/devices'

interface DeviceDebugLogPanelProps {
  deviceId: string
  enabled: boolean
  onEnabledChange: (enabled: boolean) => void
}

type ActiveTab = 'all' | DebugLogCategory
type ConnectionState = 'loading' | 'connecting' | 'live' | 'reconnecting' | 'off' | 'failed'

const backoffMs = [1_000, 2_000, 5_000]
const tabItems: Array<{ key: ActiveTab; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'conversation', label: '对话' },
  { key: 'model_tool', label: '模型与工具' },
  { key: 'audio', label: '音频链路' },
  { key: 'device', label: '设备事件' },
]
const levelLabels: Record<DebugLogLevel, string> = {
  debug: '调试',
  info: '信息',
  warning: '警告',
  error: '错误',
}
const levelColors: Record<DebugLogLevel, string> = {
  debug: 'default',
  info: 'blue',
  warning: 'orange',
  error: 'red',
}
const connectionPresentation: Record<ConnectionState, { status: 'default' | 'processing' | 'success' | 'warning' | 'error'; text: string }> = {
  loading: { status: 'processing', text: '加载历史' },
  connecting: { status: 'processing', text: '正在连接' },
  live: { status: 'success', text: '实时' },
  reconnecting: { status: 'warning', text: '重连中' },
  off: { status: 'default', text: '已关闭' },
  failed: { status: 'error', text: '加载失败' },
}

function sortedNewest(events: Iterable<DebugLogEvent>) {
  return Array.from(events).sort((left, right) => (
    left.receivedAt - right.receivedAt || compareRedisCursor(left.cursor, right.cursor)
  )).slice(-1_000)
}

function compareDecimal(left: string, right: string) {
  const normalizedLeft = left.replace(/^0+(?=\d)/, '')
  const normalizedRight = right.replace(/^0+(?=\d)/, '')
  return normalizedLeft.length - normalizedRight.length || normalizedLeft.localeCompare(normalizedRight)
}

function compareRedisCursor(left: string, right: string) {
  const leftParts = /^(\d+)-(\d+)$/.exec(left)
  const rightParts = /^(\d+)-(\d+)$/.exec(right)
  if (!leftParts || !rightParts) return left.localeCompare(right)
  return compareDecimal(leftParts[1], rightParts[1]) || compareDecimal(leftParts[2], rightParts[2])
}

function newestCursor(events: DebugLogEvent[], fallback: string) {
  return events.reduce(
    (current, event) => compareRedisCursor(event.cursor, current) > 0 ? event.cursor : current,
    fallback,
  )
}

function isAbortError(reason: unknown) {
  return reason instanceof DOMException && reason.name === 'AbortError'
    || reason instanceof Error && reason.name === 'AbortError'
}

function safeJson(value: unknown) {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return '详情无法序列化'
  }
}

function LogRow({ event }: { event: DebugLogEvent }) {
  return (
    <article className="debug-log-row">
      <div className="debug-log-meta">
        <time dateTime={new Date(event.occurredAt).toISOString()}>
          {new Date(event.occurredAt).toLocaleTimeString('zh-CN', { hour12: false })}
        </time>
        <Tag color={levelColors[event.level]}>{levelLabels[event.level]}</Tag>
        <Typography.Text code>{event.eventType}</Typography.Text>
        {event.durationMs !== null && <Typography.Text type="secondary">{event.durationMs} ms</Typography.Text>}
      </div>
      <div className="debug-log-summary" data-testid="debug-log-summary">{event.summary}</div>
      <details className="debug-log-details">
        <summary>详情</summary>
        <pre>{safeJson(event.details)}</pre>
      </details>
    </article>
  )
}

export function DeviceDebugLogPanel({ deviceId, enabled, onEnabledChange }: DeviceDebugLogPanelProps) {
  const [events, setEvents] = useState<DebugLogEvent[]>([])
  const [loadedDeviceId, setLoadedDeviceId] = useState<string | null>(null)
  const [connectionState, setConnectionState] = useState<ConnectionState>('loading')
  const [activeTab, setActiveTab] = useState<ActiveTab>('all')
  const [autoScroll, setAutoScroll] = useState(true)
  const [toggleLoading, setToggleLoading] = useState(false)
  const [historyError, setHistoryError] = useState('')
  const [historyReload, setHistoryReload] = useState(0)
  const [messageApi, messageContext] = message.useMessage()
  const viewportRef = useRef<HTMLDivElement>(null)
  const cursorRef = useRef('0-0')
  const generationRef = useRef(0)
  const toggleGenerationRef = useRef(0)

  useEffect(() => {
    const generation = ++generationRef.current
    const controller = new AbortController()
    cursorRef.current = '0-0'
    setEvents([])
    setLoadedDeviceId(null)
    setHistoryError('')
    setConnectionState('loading')
    setActiveTab('all')
    setAutoScroll(true)
    toggleGenerationRef.current += 1
    setToggleLoading(false)

    void getDeviceDebugLogHistory(deviceId, { signal: controller.signal }).then((history) => {
      if (controller.signal.aborted || generationRef.current !== generation) return
      cursorRef.current = newestCursor(history.events, history.lastCursor)
      setEvents(sortedNewest(new Map(history.events.map((event) => [event.cursor, event])).values()))
      setLoadedDeviceId(deviceId)
      setConnectionState('off')
    }).catch((reason) => {
      if (controller.signal.aborted || generationRef.current !== generation) return
      setHistoryError(reason instanceof Error ? reason.message : '日志历史加载失败')
      setConnectionState('failed')
    })

    return () => controller.abort()
  }, [deviceId, historyReload])

  useEffect(() => {
    if (loadedDeviceId !== deviceId) return
    if (!enabled) {
      setConnectionState('off')
      return
    }

    const generation = generationRef.current
    let cancelled = false
    let controller: AbortController | null = null
    let retryTimer: number | null = null
    let failures = 0

    const connect = async () => {
      if (cancelled || generationRef.current !== generation) return
      controller = new AbortController()
      setConnectionState(failures === 0 ? 'connecting' : 'reconnecting')
      try {
        await streamDeviceDebugLogs(deviceId, cursorRef.current, {
          signal: controller.signal,
          onOpen: () => {
            if (cancelled || generationRef.current !== generation) return
            failures = 0
            setConnectionState('live')
          },
          onEvent: (event) => {
            if (cancelled || generationRef.current !== generation) return
            if (compareRedisCursor(event.cursor, cursorRef.current) > 0) cursorRef.current = event.cursor
            setEvents((current) => {
              const merged = new Map(current.map((item) => [item.cursor, item]))
              merged.set(event.cursor, event)
              return sortedNewest(merged.values())
            })
          },
        })
      } catch (reason) {
        if (cancelled || controller.signal.aborted || isAbortError(reason) || generationRef.current !== generation) return
      }
      if (cancelled || controller.signal.aborted || generationRef.current !== generation) return
      const delay = backoffMs[Math.min(failures, backoffMs.length - 1)]
      failures += 1
      setConnectionState('reconnecting')
      retryTimer = window.setTimeout(() => { void connect() }, delay)
    }

    void connect()
    return () => {
      cancelled = true
      controller?.abort()
      if (retryTimer !== null) window.clearTimeout(retryTimer)
    }
  }, [deviceId, enabled, loadedDeviceId])

  const visibleEvents = useMemo(
    () => activeTab === 'all' ? events : events.filter((event) => event.category === activeTab),
    [activeTab, events],
  )

  useEffect(() => {
    if (!autoScroll) return
    const viewport = viewportRef.current
    if (!viewport) return
    if (typeof viewport.scrollTo === 'function') {
      viewport.scrollTo({ top: viewport.scrollHeight, behavior: 'smooth' })
    } else {
      viewport.scrollTop = viewport.scrollHeight
    }
  }, [autoScroll, visibleEvents])

  async function updateEnabled(nextEnabled: boolean) {
    if (toggleLoading) return
    const generation = generationRef.current
    const toggleGeneration = ++toggleGenerationRef.current
    setToggleLoading(true)
    try {
      await setDeviceDebugLogging(deviceId, nextEnabled)
      if (generationRef.current === generation && toggleGenerationRef.current === toggleGeneration) {
        onEnabledChange(nextEnabled)
      }
    } catch (reason) {
      if (generationRef.current === generation && toggleGenerationRef.current === toggleGeneration) {
        messageApi.error(reason instanceof Error ? reason.message : '日志开关更新失败')
      }
    } finally {
      if (generationRef.current === generation && toggleGenerationRef.current === toggleGeneration) {
        setToggleLoading(false)
      }
    }
  }

  const connection = connectionPresentation[connectionState]
  const toolbar = (
    <div className="debug-log-toolbar">
      <Space size="small" wrap>
        <Switch
          aria-label="记录调试日志"
          checked={enabled}
          loading={toggleLoading}
          disabled={toggleLoading}
          onChange={(checked) => void updateEnabled(checked)}
        />
        <Badge status={connection.status} text={connection.text} />
      </Space>
      <Space size="small" wrap>
        <Button
          icon={autoScroll ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
          aria-label={autoScroll ? '暂停自动滚动' : '恢复自动滚动'}
          onClick={() => setAutoScroll((current) => !current)}
        >{autoScroll ? '暂停滚动' : '恢复滚动'}</Button>
        <Button icon={<DeleteOutlined />} aria-label="清空当前视图" onClick={() => setEvents([])}>清空当前视图</Button>
      </Space>
    </div>
  )

  return (
    <Card
      className="surface-card debug-log-card"
      data-testid="device-debug-log-panel"
      title="实时调试日志"
      extra={toolbar}
    >
      {messageContext}
      {historyError && (
        <Alert
          className="debug-log-history-error"
          type="error"
          showIcon
          message={historyError}
          action={<Button size="small" icon={<ReloadOutlined />} onClick={() => setHistoryReload((value) => value + 1)}>重试</Button>}
        />
      )}
      <Tabs
        activeKey={activeTab}
        items={tabItems.map((item) => ({ key: item.key, label: item.label }))}
        onChange={(key) => setActiveTab(key as ActiveTab)}
      />
      {activeTab === 'model_tool' && (
        <Alert
          className="debug-log-thought-notice"
          type="info"
          showIcon
          message="模型内部思考不可用"
          description="当前系统只展示模型处理阶段、最终回复、耗时和工具调用，不展示或推测模型内部思考。"
        />
      )}
      <div className="debug-log-viewport" data-testid="debug-log-viewport" ref={viewportRef}>
        {visibleEvents.length === 0
          ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无日志" />
          : visibleEvents.map((event) => <LogRow key={event.cursor} event={event} />)}
      </div>
    </Card>
  )
}
