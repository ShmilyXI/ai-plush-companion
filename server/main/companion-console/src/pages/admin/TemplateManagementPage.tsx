import { Button, Form, Input, message, Modal, Space, Table } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { createTemplate, deleteTemplate, listTemplates, updateTemplate, type AdminTemplate, type TemplateInput } from '../../api/admin'
import { AdminPage, ConfirmButton } from './AdminPage'
import { adminErrorMessage } from './adminErrors'

export function TemplateManagementPage() {
  const [rows, setRows] = useState<AdminTemplate[]>([]), [query, setQuery] = useState(''), [page, setPage] = useState(1), [total, setTotal] = useState(0), [loading, setLoading] = useState(true), [error, setError] = useState(''), [editing, setEditing] = useState<AdminTemplate | null | undefined>(undefined), [saving, setSaving] = useState(false)
  const [form] = Form.useForm<TemplateInput>()
  const load = useCallback((signal?: AbortSignal) => { setLoading(true); setError(''); return listTemplates(query, page, 20, { signal }).then((data) => { setRows(data.list); setTotal(data.total) }).catch((reason) => { if (!signal?.aborted) setError(reason instanceof Error ? reason.message : '模板加载失败') }).finally(() => { if (!signal?.aborted) setLoading(false) }) }, [page, query])
  useEffect(() => { const controller = new AbortController(); void load(controller.signal); return () => controller.abort() }, [load])
  function openEditor(template: AdminTemplate | null) { setEditing(template); form.setFieldsValue(template ? { agentCode: template.agentCode || '', agentName: template.agentName, systemPrompt: template.systemPrompt } : { agentCode: '', agentName: '', systemPrompt: '' }) }
  async function save() { const input = await form.validateFields(); setSaving(true); setError(''); try { if (editing) await updateTemplate(editing.id, input); else await createTemplate(input); message.success(editing ? '模板已更新' : '模板已创建'); setEditing(undefined); await load() } catch (reason) { setError(adminErrorMessage(reason, '模板保存失败')) } finally { setSaving(false) } }
  return <AdminPage title="角色模板" loading={loading} error={error} onSearch={(value) => { setQuery(value.trim()); setPage(1) }} actions={<Button type="primary" onClick={() => openEditor(null)}>新建模板</Button>}><Table rowKey="id" dataSource={rows} scroll={{ x: 680 }} pagination={{ current: page, pageSize: 20, total, showSizeChanger: false, onChange: setPage }} columns={[
    { title: '模板名称', dataIndex: 'agentName' }, { title: '模板代码', dataIndex: 'agentCode' }, { title: '说明', dataIndex: 'description', ellipsis: true }, { title: '排序', dataIndex: 'sort' }, { title: '操作', render: (_, row) => <Space><Button onClick={() => openEditor(row)}>编辑</Button><ConfirmButton danger label="删除" title="确认删除该模板？" onConfirm={async () => { await deleteTemplate(row.id); message.success('模板已删除'); await load() }} /></Space> },
  ]} /><Modal title={editing ? '编辑模板' : '新建模板'} open={editing !== undefined} onCancel={() => setEditing(undefined)} onOk={save} confirmLoading={saving} okText="保存" destroyOnHidden><Form form={form} layout="vertical"><Form.Item name="agentCode" label="模板代码" rules={[{ required: true }]}><Input disabled={Boolean(editing)} /></Form.Item><Form.Item name="agentName" label="模板名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="systemPrompt" label="角色设定"><Input.TextArea rows={6} maxLength={10000} /></Form.Item></Form></Modal></AdminPage>
}
