import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as debugLogApi from '../../api/deviceDebugLogs'
import type { DebugLogCategory, DebugLogEvent } from '../../api/deviceDebugLogs'
import * as deviceApi from '../../api/devices'
import { DeviceDebugLogPanel } from './DeviceDebugLogPanel'

vi.mock('../../api/deviceDebugLogs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/deviceDebugLogs')>()
  return {
    ...actual,
    getDeviceDebugLogHistory: vi.fn(),
    streamDeviceDebugLogs: vi.fn(),
  }
})

function event(
  cursor: string,
  category: DebugLogCategory,
  summary: string,
  receivedAt = Number(cursor.split('-')[0]),
  eventType = `${category}.event`,
): DebugLogEvent {
  return {
    cursor,
    deviceId: 'device-a',
    occurredAt: receivedAt,
    receivedAt,
    sessionId: null,
    sentenceId: null,
    category,
    eventType,
    level: category === 'device' ? 'warning' : 'info',
    summary,
    details: { cursor },
    durationMs: category === 'model_tool' ? 42 : null,
  }
}

function pendingStream() {
  return vi.fn((_deviceId: string, _after: string, options: Parameters<typeof debugLogApi.streamDeviceDebugLogs>[2]) => (
    new Promise<void>((_resolve, reject) => {
      options.onOpen?.()
      options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    })
  ))
}

function renderPanel(props: Partial<React.ComponentProps<typeof DeviceDebugLogPanel>> = {}) {
  return render(
    <DeviceDebugLogPanel
      deviceId="device-a"
      enabled
      onEnabledChange={vi.fn()}
      {...props}
    />,
  )
}

describe('DeviceDebugLogPanel', () => {
  beforeEach(() => {
    vi.mocked(debugLogApi.getDeviceDebugLogHistory).mockResolvedValue({ events: [], lastCursor: '0-0' })
    vi.mocked(debugLogApi.streamDeviceDebugLogs).mockImplementation(pendingStream())
    vi.spyOn(deviceApi, 'setDeviceDebugLogging').mockResolvedValue(undefined)
    Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
      configurable: true,
      value: vi.fn(),
    })
  })

  it('renders history before connecting and appends live events', async () => {
    let openStream!: () => void
    vi.mocked(debugLogApi.getDeviceDebugLogHistory).mockResolvedValue({
      events: [event('1-0', 'conversation', '历史消息')],
      lastCursor: '1-0',
    })
    vi.mocked(debugLogApi.streamDeviceDebugLogs).mockImplementation((_id, after, options) => {
      expect(screen.getByText('历史消息')).toBeVisible()
      expect(after).toBe('1-0')
      openStream = () => options.onEvent(event('2-0', 'conversation', '实时消息'))
      return new Promise<void>((_resolve, reject) => {
        options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    })

    renderPanel()

    expect(await screen.findByText('历史消息')).toBeVisible()
    act(() => openStream())
    expect(await screen.findByText('实时消息')).toBeVisible()
  })

  it('deduplicates matching cursors across history and live events', async () => {
    vi.mocked(debugLogApi.getDeviceDebugLogHistory).mockResolvedValue({
      events: [event('1-0', 'conversation', '同一条日志')],
      lastCursor: '1-0',
    })
    vi.mocked(debugLogApi.streamDeviceDebugLogs).mockImplementation(async (_id, _after, options) => {
      options.onOpen?.()
      options.onEvent(event('1-0', 'conversation', '同一条日志'))
      await new Promise<void>((_resolve, reject) => {
        options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    })

    renderPanel()

    expect(await screen.findAllByText('同一条日志')).toHaveLength(1)
  })

  it('does not move the continuation cursor backwards for an older duplicate event', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(debugLogApi.getDeviceDebugLogHistory).mockResolvedValue({
        events: [event('5-0', 'conversation', '最新历史', 5)],
        lastCursor: '5-0',
      })
      vi.mocked(debugLogApi.streamDeviceDebugLogs)
        .mockImplementationOnce(async (_id, _after, options) => {
          options.onEvent(event('4-0', 'conversation', '迟到重复', 4))
          throw new Error('断线')
        })
        .mockImplementation(pendingStream())
      const view = renderPanel()

      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      await act(async () => { await vi.advanceTimersByTimeAsync(1_000) })

      expect(debugLogApi.streamDeviceDebugLogs).toHaveBeenNthCalledWith(2, 'device-a', '5-0', expect.any(Object))
      view.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('filters events through all five tabs', async () => {
    vi.mocked(debugLogApi.getDeviceDebugLogHistory).mockResolvedValue({
      events: [
        event('1-0', 'conversation', '对话事件'),
        event('2-0', 'model_tool', '模型事件'),
        event('3-0', 'audio', '音频事件'),
        event('4-0', 'device', '设备事件'),
      ],
      lastCursor: '4-0',
    })
    renderPanel()
    const user = userEvent.setup()

    expect(await screen.findByText('对话事件')).toBeVisible()
    expect(screen.getByText('模型事件')).toBeVisible()
    expect(screen.getByText('音频事件')).toBeVisible()
    expect(screen.getAllByText('设备事件')).toHaveLength(2)

    for (const [tab, visible, hidden] of [
      ['对话', '对话事件', '模型事件'],
      ['模型与工具', '模型事件', '对话事件'],
      ['音频链路', '音频事件', '设备事件'],
      ['设备事件', '设备事件', '音频事件'],
    ]) {
      await user.click(screen.getByRole('tab', { name: tab }))
      const viewport = screen.getByTestId('debug-log-viewport')
      expect(within(viewport).getByText(visible)).toBeVisible()
      expect(within(viewport).queryByText(hidden)).not.toBeInTheDocument()
    }

    await user.click(screen.getByRole('tab', { name: '全部' }))
    expect(screen.getByText('对话事件')).toBeVisible()
    expect(within(screen.getByTestId('debug-log-viewport')).getByText('设备事件')).toBeVisible()
  })

  it('updates the logging switch and rolls back on failure', async () => {
    const onEnabledChange = vi.fn()
    const switchRequest = vi.mocked(deviceApi.setDeviceDebugLogging)
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('开关更新失败'))
    const view = renderPanel({ enabled: false, onEnabledChange })
    const user = userEvent.setup()

    const toggle = await screen.findByRole('switch', { name: '记录调试日志' })
    await user.click(toggle)
    expect(switchRequest).toHaveBeenCalledWith('device-a', true)
    expect(onEnabledChange).toHaveBeenCalledWith(true)

    view.rerender(<DeviceDebugLogPanel deviceId="device-a" enabled onEnabledChange={onEnabledChange} />)
    await user.click(screen.getByRole('switch', { name: '记录调试日志' }))
    expect(await screen.findByText('开关更新失败')).toBeVisible()
    expect(screen.getByRole('switch', { name: '记录调试日志' })).toBeChecked()
    expect(onEnabledChange).toHaveBeenCalledTimes(1)
  })

  it('pauses auto-scroll without pausing incoming events', async () => {
    let push!: (next: DebugLogEvent) => void
    vi.mocked(debugLogApi.streamDeviceDebugLogs).mockImplementation((_id, _after, options) => {
      options.onOpen?.()
      push = options.onEvent
      return new Promise<void>((_resolve, reject) => {
        options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    })
    renderPanel()
    const user = userEvent.setup()

    await waitFor(() => expect(debugLogApi.streamDeviceDebugLogs).toHaveBeenCalledOnce())
    await user.click(screen.getByRole('button', { name: '暂停自动滚动' }))
    const scrollTo = vi.mocked(HTMLElement.prototype.scrollTo)
    scrollTo.mockClear()
    act(() => push(event('2-0', 'conversation', '暂停期间收到')))

    expect(await screen.findByText('暂停期间收到')).toBeVisible()
    expect(scrollTo).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '恢复自动滚动' })).toBeVisible()
  })

  it('clears only local events and continues from the retained cursor', async () => {
    let push!: (next: DebugLogEvent) => void
    vi.mocked(debugLogApi.getDeviceDebugLogHistory).mockResolvedValue({
      events: [event('5-0', 'conversation', '即将清空')],
      lastCursor: '5-0',
    })
    vi.mocked(debugLogApi.streamDeviceDebugLogs).mockImplementation((_id, after, options) => {
      expect(after).toBe('5-0')
      push = options.onEvent
      return new Promise<void>((_resolve, reject) => {
        options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    })
    renderPanel()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '清空当前视图' }))
    expect(screen.queryByText('即将清空')).not.toBeInTheDocument()
    expect(deviceApi.setDeviceDebugLogging).not.toHaveBeenCalled()
    act(() => push(event('6-0', 'device', '清空后新事件')))
    expect(await screen.findByText('清空后新事件')).toBeVisible()
  })

  it('reconnects after 1, 2, and 5 seconds from the latest cursor', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(debugLogApi.getDeviceDebugLogHistory).mockResolvedValue({ events: [], lastCursor: '10-0' })
      vi.mocked(debugLogApi.streamDeviceDebugLogs)
        .mockRejectedValueOnce(new Error('断线一'))
        .mockImplementationOnce(async (_id, after, options) => {
          expect(after).toBe('10-0')
          options.onEvent(event('11-0', 'device', '续传事件'))
          throw new Error('断线二')
        })
        .mockRejectedValueOnce(new Error('断线三'))
        .mockImplementation(pendingStream())
      const view = renderPanel()

      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      expect(debugLogApi.streamDeviceDebugLogs).toHaveBeenCalledTimes(1)
      await act(async () => { await vi.advanceTimersByTimeAsync(999) })
      expect(debugLogApi.streamDeviceDebugLogs).toHaveBeenCalledTimes(1)
      await act(async () => { await vi.advanceTimersByTimeAsync(1) })
      expect(debugLogApi.streamDeviceDebugLogs).toHaveBeenNthCalledWith(2, 'device-a', '10-0', expect.any(Object))
      await act(async () => { await vi.advanceTimersByTimeAsync(2_000) })
      expect(debugLogApi.streamDeviceDebugLogs).toHaveBeenNthCalledWith(3, 'device-a', '11-0', expect.any(Object))
      await act(async () => { await vi.advanceTimersByTimeAsync(4_999) })
      expect(debugLogApi.streamDeviceDebugLogs).toHaveBeenCalledTimes(3)
      await act(async () => { await vi.advanceTimersByTimeAsync(1) })
      expect(debugLogApi.streamDeviceDebugLogs).toHaveBeenNthCalledWith(4, 'device-a', '11-0', expect.any(Object))
      view.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not stream while disabled but loads history and connects when enabled', async () => {
    vi.mocked(debugLogApi.getDeviceDebugLogHistory).mockResolvedValue({
      events: [event('3-0', 'conversation', '关闭时的旧记录')],
      lastCursor: '3-0',
    })
    const view = renderPanel({ enabled: false })

    expect(await screen.findByText('关闭时的旧记录')).toBeVisible()
    expect(debugLogApi.streamDeviceDebugLogs).not.toHaveBeenCalled()
    view.rerender(<DeviceDebugLogPanel deviceId="device-a" enabled onEnabledChange={vi.fn()} />)
    await waitFor(() => expect(debugLogApi.streamDeviceDebugLogs).toHaveBeenCalledWith(
      'device-a',
      '3-0',
      expect.any(Object),
    ))
    expect(debugLogApi.getDeviceDebugLogHistory).toHaveBeenCalledOnce()
  })

  it('aborts immediately when disabled and preserves visible events', async () => {
    let signal!: AbortSignal
    vi.mocked(debugLogApi.getDeviceDebugLogHistory).mockResolvedValue({
      events: [event('1-0', 'conversation', '保留事件')],
      lastCursor: '1-0',
    })
    vi.mocked(debugLogApi.streamDeviceDebugLogs).mockImplementation((_id, _after, options) => {
      signal = options.signal!
      return new Promise<void>((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    })
    const view = renderPanel()
    await waitFor(() => expect(signal).toBeDefined())

    view.rerender(<DeviceDebugLogPanel deviceId="device-a" enabled={false} onEnabledChange={vi.fn()} />)

    expect(signal.aborted).toBe(true)
    expect(screen.getByText('保留事件')).toBeVisible()
    expect(screen.getByText('已关闭')).toBeVisible()
  })

  it('always explains that internal model thoughts are unavailable', async () => {
    renderPanel()
    const user = userEvent.setup()

    await user.click(screen.getByRole('tab', { name: '模型与工具' }))

    expect(screen.getByText('模型内部思考不可用')).toBeVisible()
    expect(screen.getByText('当前系统只展示模型处理阶段、最终回复、耗时和工具调用，不展示或推测模型内部思考。')).toBeVisible()
  })

  it('sorts events and keeps only the newest 1000', async () => {
    const events = Array.from({ length: 1_002 }, (_, index) => event(
      `${index + 1}-0`,
      'conversation',
      `事件 ${index + 1}`,
      index + 1,
    ))
    vi.mocked(debugLogApi.getDeviceDebugLogHistory).mockResolvedValue({ events: events.reverse(), lastCursor: '1002-0' })
    renderPanel()

    const viewport = await screen.findByTestId('debug-log-viewport')
    await waitFor(() => expect(within(viewport).getAllByRole('article')).toHaveLength(1_000))
    expect(screen.queryByText('事件 1')).not.toBeInTheDocument()
    expect(screen.queryByText('事件 2')).not.toBeInTheDocument()
    const summaries = within(viewport).getAllByRole('article').map((row) => within(row).getByTestId('debug-log-summary').textContent)
    expect(summaries[0]).toBe('事件 3')
    expect(summaries.at(-1)).toBe('事件 1002')
  }, 15_000)

  it('aborts and resets state when the device id changes', async () => {
    const signals: AbortSignal[] = []
    vi.mocked(debugLogApi.getDeviceDebugLogHistory).mockImplementation(async (deviceId) => ({
      events: [event('1-0', 'device', `${deviceId} 的日志`)],
      lastCursor: '1-0',
    }))
    vi.mocked(debugLogApi.streamDeviceDebugLogs).mockImplementation((_id, _after, options) => {
      signals.push(options.signal!)
      return new Promise<void>((_resolve, reject) => {
        options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    })
    const view = renderPanel()
    expect(await screen.findByText('device-a 的日志')).toBeVisible()
    await waitFor(() => expect(signals).toHaveLength(1))

    view.rerender(<DeviceDebugLogPanel deviceId="device-b" enabled onEnabledChange={vi.fn()} />)

    expect(signals[0].aborted).toBe(true)
    expect(screen.queryByText('device-a 的日志')).not.toBeInTheDocument()
    expect(await screen.findByText('device-b 的日志')).toBeVisible()
  })

  it('reconnects after a normal stream EOF', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(debugLogApi.streamDeviceDebugLogs)
        .mockResolvedValueOnce(undefined)
        .mockImplementation(pendingStream())
      const view = renderPanel()

      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      expect(debugLogApi.streamDeviceDebugLogs).toHaveBeenCalledOnce()
      await act(async () => { await vi.advanceTimersByTimeAsync(1_000) })
      expect(debugLogApi.streamDeviceDebugLogs).toHaveBeenCalledTimes(2)
      view.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('disables repeated switch clicks while the update is pending', async () => {
    let finish!: () => void
    vi.mocked(deviceApi.setDeviceDebugLogging).mockReturnValue(new Promise<void>((resolve) => { finish = resolve }))
    renderPanel({ enabled: false })

    fireEvent.click(await screen.findByRole('switch', { name: '记录调试日志' }))
    fireEvent.click(screen.getByRole('switch', { name: '记录调试日志' }))
    expect(deviceApi.setDeviceDebugLogging).toHaveBeenCalledOnce()
    await act(async () => finish())
  })
})
