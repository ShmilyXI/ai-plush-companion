import { SoundOutlined } from '@ant-design/icons'
import { PageContainer } from '@ant-design/pro-components'
import { Alert, Button, Card, Input, Select, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useRef, useState } from 'react'

import {
  listVolcengineVoices,
  type VolcengineResourceId,
  type VolcengineVoice,
} from '../../api/volcengineVoices'

const PAGE_SIZE = 20
type VoiceRow = VolcengineVoice & { rowKey: string }
const versions = [
  { label: '语音合成 1.0', value: 'seed-tts-1.0' },
  { label: '语音合成 2.0', value: 'seed-tts-2.0' },
] satisfies { label: string; value: VolcengineResourceId }[]

function errorText(reason: unknown, fallback: string) {
  const value = reason instanceof Error ? reason.message.trim() : ''
  return value && value.length <= 160 ? value : fallback
}

function isHttpUrl(value: string) {
  try {
    const url = new URL(value)
    return (url.protocol === 'http:' || url.protocol === 'https:') && Boolean(url.hostname)
  } catch {
    return false
  }
}

export function TimbreManagementPanel() {
  const [resourceId, setResourceId] = useState<VolcengineResourceId>('seed-tts-1.0')
  const [searchInput, setSearchInput] = useState('')
  const [name, setName] = useState('')
  const [page, setPage] = useState(1)
  const [rows, setRows] = useState<VoiceRow[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [playingKey, setPlayingKey] = useState<string | null>(null)
  const mounted = useRef(false)
  const controllerRef = useRef<AbortController | null>(null)
  const requestSequence = useRef(0)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const previewSequence = useRef(0)

  const load = useCallback(async (
    nextResourceId: VolcengineResourceId,
    nextName: string,
    nextPage: number,
  ) => {
    const sequence = ++requestSequence.current
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    setLoading(true)
    setError('')
    try {
      const result = await listVolcengineVoices(
        { resourceId: nextResourceId, page: nextPage, limit: PAGE_SIZE, name: nextName },
        { signal: controller.signal },
      )
      if (mounted.current && requestSequence.current === sequence && !controller.signal.aborted) {
        setRows(result.list.map((row, index) => ({
          ...row,
          rowKey: `${nextResourceId}:${nextPage}:${index}:${row.id}`,
        })))
        setTotal(result.total)
      }
    } catch (reason) {
      if (mounted.current && requestSequence.current === sequence && !controller.signal.aborted) {
        setRows([])
        setTotal(0)
        setError(errorText(reason, '火山音色列表加载失败'))
      }
    } finally {
      if (mounted.current && requestSequence.current === sequence && !controller.signal.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      requestSequence.current += 1
      controllerRef.current?.abort()
      previewSequence.current += 1
      const audio = audioRef.current
      if (audio) {
        audio.onerror = null
        audio.onended = null
        audio.pause()
        audio.currentTime = 0
        audio.src = ''
        audioRef.current = null
      }
    }
  }, [])

  useEffect(() => {
    void load(resourceId, name, page)
  }, [load, name, page, resourceId])

  function stopPreview() {
    previewSequence.current += 1
    const audio = audioRef.current
    if (audio) {
      audio.onerror = null
      audio.onended = null
      audio.pause()
      audio.currentTime = 0
      audio.src = ''
    }
    setPlayingKey(null)
  }

  async function preview(row: VoiceRow) {
    if (!row.trialUrl) return
    const identity = row.rowKey
    if (playingKey === identity) {
      stopPreview()
      return
    }
    const sequence = ++previewSequence.current
    const current = audioRef.current
    if (current) {
      current.onerror = null
      current.onended = null
      current.pause()
      current.currentTime = 0
      current.src = ''
    }
    setPlayingKey(null)
    if (!isHttpUrl(row.trialUrl)) {
      setError('试听失败')
      return
    }
    const audio = current ?? new Audio()
    audioRef.current = audio
    audio.onerror = () => {
      if (mounted.current && previewSequence.current === sequence) {
        setPlayingKey(null)
        setError('试听失败')
      }
    }
    audio.onended = () => {
      if (mounted.current && previewSequence.current === sequence) setPlayingKey(null)
    }
    audio.src = row.trialUrl
    setError('')
    try {
      await audio.play()
      if (mounted.current && previewSequence.current === sequence) setPlayingKey(identity)
    } catch {
      if (mounted.current && previewSequence.current === sequence) {
        setPlayingKey(null)
        setError('试听失败')
      }
    }
  }

  const columns: ColumnsType<VoiceRow> = [
    { title: '名称', dataIndex: 'name', width: 140 },
    { title: '音色编码', dataIndex: 'voiceType', width: 240 },
    { title: '性别', dataIndex: 'gender', width: 80, render: (value: string | null) => value || '-' },
    { title: '年龄', dataIndex: 'age', width: 90, render: (value: string | null) => value || '-' },
    { title: '语言', dataIndex: 'languages', width: 140, render: (value: string | null) => value || '-' },
    {
      title: '标签', dataIndex: 'tags', width: 200,
      render: (tags: string[]) => tags.length ? <Space size={[0, 4]} wrap>{tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}</Space> : '-',
    },
    { title: '描述', dataIndex: 'description', render: (value: string | null) => value || '-' },
    {
      title: '试听', width: 110, fixed: 'right',
      render: (_, row) => row.trialUrl ? <Button
        aria-label={`${playingKey === row.rowKey ? '停止' : '试听'}${row.name}`}
        icon={<SoundOutlined />}
        type="text"
        onClick={() => void preview(row)}
      >{playingKey === row.rowKey ? '停止' : '试听'}</Button> : <Typography.Text type="secondary">暂无试听</Typography.Text>,
    },
  ]

  function search() {
    setName(searchInput.trim())
    setPage(1)
  }

  function reset() {
    setSearchInput('')
    setName('')
    setPage(1)
    if (!name && page === 1) void load(resourceId, '', 1)
  }

  return <section>
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      {error && <Alert type="error" showIcon message={error} />}
      <Card>
        <Space wrap style={{ marginBottom: 16 }}>
          <Select
            aria-label="语音合成模型"
            value={resourceId}
            style={{ width: 220 }}
            options={versions}
            onChange={(value: VolcengineResourceId) => {
              stopPreview()
              setResourceId(value)
              setPage(1)
            }}
          />
          <Input.Search
            aria-label="音色名称"
            allowClear
            enterButton="搜索"
            placeholder="按音色名称搜索"
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            onSearch={search}
          />
          <Button onClick={reset}>重置</Button>
        </Space>
        <Table
          rowKey="rowKey"
          loading={loading}
          dataSource={rows}
          columns={columns}
          scroll={{ x: 1200 }}
          pagination={{
            current: page,
            total,
            pageSize: PAGE_SIZE,
            showSizeChanger: false,
            onChange: setPage,
          }}
        />
      </Card>
    </Space>
  </section>
}

export function TimbreManagementPage() {
  return <PageContainer title={<h1 className="page-container-title">音色库</h1>}><TimbreManagementPanel /></PageContainer>
}
