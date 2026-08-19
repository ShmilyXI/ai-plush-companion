import { CopyOutlined, DeleteOutlined, PlusOutlined, RightOutlined } from '@ant-design/icons'
import { PageContainer } from '@ant-design/pro-components'
import { Alert, Button, Card, Empty, Form, Input, List, Modal, Select, Space, Spin, Tag, Typography, message } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { createProfile, deleteProfile, listProfiles, listTemplates, type CompanionProfile, type CompanionTemplate } from '../../api/profiles'

export function ProfileListPage() {
  const [profiles, setProfiles] = useState<CompanionProfile[]>([])
  const [templates, setTemplates] = useState<CompanionTemplate[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [templateError, setTemplateError] = useState('')
  const [open, setOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [deleting, setDeleting] = useState<CompanionProfile | null>(null)
  const [form] = Form.useForm<{ templateId: string; name: string }>()
  const [messageApi, context] = message.useMessage()
  const mounted = useRef(false)
  const request = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const navigate = useNavigate()

  const load = useCallback(async () => {
    const sequence = ++request.current
    controller.current?.abort()
    const nextController = new AbortController()
    controller.current = nextController
    setLoading(true); setError(''); setTemplateError('')
    const [profileResult, templateResult] = await Promise.allSettled([
      listProfiles({ signal: nextController.signal }),
      listTemplates({ signal: nextController.signal }),
    ])
    if (!mounted.current || request.current !== sequence || nextController.signal.aborted) return
    if (profileResult.status === 'fulfilled') setProfiles(profileResult.value)
    else setError(profileResult.reason instanceof Error ? profileResult.reason.message : '角色列表加载失败')
    if (templateResult.status === 'fulfilled') {
      setTemplates(Array.from(new Map(templateResult.value.map((template) => [template.id, template])).values()))
    } else setTemplateError(templateResult.reason instanceof Error ? templateResult.reason.message : '角色模板加载失败')
    setLoading(false)
  }, [])

  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false; request.current += 1; controller.current?.abort() } }, [load])

  async function create(values: { templateId: string; name: string }) {
    const nextController = new AbortController()
    controller.current = nextController
    setCreating(true)
    try {
      const id = await createProfile(values.templateId, values.name, { signal: nextController.signal })
      if (!mounted.current || nextController.signal.aborted) return
      setOpen(false); messageApi.success('角色已复制'); navigate(`/profiles/${encodeURIComponent(id)}`)
    } catch (reason) { if (mounted.current && !nextController.signal.aborted) messageApi.error(reason instanceof Error ? reason.message : '创建失败') }
    finally { if (mounted.current) setCreating(false) }
  }

  async function remove() {
    if (!deleting) return
    const target = deleting
    const nextController = new AbortController()
    controller.current = nextController
    setCreating(true)
    try {
      await deleteProfile(target.id, { signal: nextController.signal })
      if (!mounted.current || nextController.signal.aborted) return
      setProfiles((items) => items.filter((item) => item.id !== target.id))
      setDeleting(null)
      messageApi.success('角色已删除')
    } catch (reason) { if (mounted.current && !nextController.signal.aborted) messageApi.error(reason instanceof Error ? reason.message : '删除失败') }
    finally { if (mounted.current) setCreating(false) }
  }

  return <PageContainer className="console-page profile-list-page" title={<h1 className="page-container-title">陪伴角色</h1>} subTitle="每个角色都有自己的关系、性格、声音和互动偏好。" extra={<Button aria-label="创建陪伴角色" type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>创建陪伴角色</Button>}>{context}
    {error && <Alert type="error" showIcon message={error} action={<Button size="small" onClick={() => void load()}>重试</Button>} />}
    {templateError && <Alert type="error" showIcon message={templateError} action={<Button size="small" onClick={() => void load()}>重试</Button>} />}
    <Spin spinning={loading}><Card className="surface-card"><List dataSource={profiles} locale={{ emptyText: <Empty description="还没有陪伴角色" /> }} renderItem={(profile) => <List.Item
      actions={[
        <Button key="delete" type="text" danger icon={<DeleteOutlined />} aria-label={`删除${profile.name}`} onClick={(event) => { event.stopPropagation(); setDeleting(profile) }} />,
        <Link key="edit" aria-label={`编辑${profile.name}`} to={`/profiles/${encodeURIComponent(profile.id)}`}><RightOutlined /></Link>,
      ]}
      className="clickable-list-item"
      role="link"
      tabIndex={0}
      onClick={(event) => { if ((event.target as HTMLElement).closest('a,button')) return; navigate(`/profiles/${encodeURIComponent(profile.id)}`) }}
      onKeyDown={(event) => { if (event.target !== event.currentTarget || (event.key !== 'Enter' && event.key !== ' ')) return; event.preventDefault(); navigate(`/profiles/${encodeURIComponent(profile.id)}`) }}
    ><List.Item.Meta title={<Space>{profile.name}<Tag color="cyan">{profile.relationMode === 'lover' ? '治愈型恋人' : '治愈型朋友'}</Tag></Space>} description={`${profile.personality || '还没有填写性格'} · ${profile.ttsVoiceName || '默认声音'}`} /></List.Item>} /></Card></Spin>
    <Modal title={<Space><CopyOutlined />从模板创建角色</Space>} open={open} confirmLoading={creating} okText="创建角色" cancelText="取消" okButtonProps={{ disabled: !templates.length }} onCancel={() => setOpen(false)} onOk={() => form.submit()} destroyOnHidden>{!templates.length && <Alert type="warning" showIcon message="暂无可用角色模板" description="模板加载完成后才能创建陪伴角色，请稍后重试。" />}<Form form={form} layout="vertical" onFinish={(values) => void create(values)}><Form.Item label="模板来源" name="templateId" rules={[{ required: true, message: '请选择模板来源' }]}><Select options={templates.map((template) => ({ label: template.name, value: template.id }))} /></Form.Item><Form.Item label="新角色名称" name="name" rules={[{ required: true, whitespace: true, message: '请输入角色名称' }, { max: 64 }]}><Input maxLength={64} /></Form.Item></Form></Modal>
    <Modal title="删除陪伴角色" open={Boolean(deleting)} confirmLoading={creating} okText="确认删除" okButtonProps={{ danger: true }} cancelText="取消" onCancel={() => setDeleting(null)} onOk={() => void remove()}><Typography.Paragraph>已绑定到设备的角色无法删除。删除成功后，角色配置和历史记录将不再显示。</Typography.Paragraph></Modal>
  </PageContainer>
}
