import { Alert, Button, DatePicker, Form, Input, InputNumber, message, Modal, Select, Space, Switch, Table, Tag } from 'antd'
import dayjs from 'dayjs'
import { useCallback, useEffect, useRef, useState } from 'react'
import { cancelSubscription, createPlan, deletePlan, grantSubscription, listPlans, listUsers, pauseSubscription, updatePlan, type AdminUser, type CompanionPlan, type PlanInput } from '../../api/admin'
import { AdminPage } from './AdminPage'
import { adminErrorMessage, reportAdminError } from './adminErrors'

const emptyPlan: PlanInput = { planCode: '', planName: '', maxDevices: 1, maxProfiles: 1, longTermMemory: 0, advancedVoice: 0, status: 1 }

export function PlanManagementPage() {
  const [plans, setPlans] = useState<CompanionPlan[]>([]), [users, setUsers] = useState<AdminUser[]>([]), [query, setQuery] = useState(''), [page, setPage] = useState(1), [total, setTotal] = useState(0), [loading, setLoading] = useState(true), [error, setError] = useState(''), [notice, setNotice] = useState(''), [editing, setEditing] = useState<CompanionPlan | null | undefined>(undefined), [grantOpen, setGrantOpen] = useState(false), [subscriptionOpen, setSubscriptionOpen] = useState(false), [saving, setSaving] = useState(false)
  const [planForm] = Form.useForm<PlanInput>(), [grantForm] = Form.useForm<{ userId: string; planId: string; expiresAt: dayjs.Dayjs }>(), [subscriptionForm] = Form.useForm<{ userId: string }>()
  const userSearchController = useRef<AbortController | null>(null), userSearchSequence = useRef(0), mounted = useRef(true)
  const load = useCallback(async (signal?: AbortSignal) => { setLoading(true); setError(''); try { const [planPage, userPage] = await Promise.all([listPlans(query, page, 20, { signal }), listUsers('', 1, 20, { signal })]); setPlans(planPage.list); setTotal(planPage.total); setUsers(userPage.list) } catch (reason) { if (!signal?.aborted) setError(reason instanceof Error ? reason.message : '套餐加载失败') } finally { if (!signal?.aborted) setLoading(false) } }, [page, query])
  useEffect(() => { const controller = new AbortController(); void load(controller.signal); return () => controller.abort() }, [load])
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; userSearchController.current?.abort() } }, [])
  function openPlan(plan: CompanionPlan | null) { setEditing(plan); planForm.setFieldsValue(plan ? { ...plan } : emptyPlan) }
  async function savePlan() { const input = await planForm.validateFields(); setSaving(true); setError(''); try { if (editing) await updatePlan(editing.id, input); else await createPlan(input); message.success(editing ? '套餐已更新' : '套餐已创建'); setEditing(undefined); await load() } catch (reason) { setError(adminErrorMessage(reason, '套餐保存失败')) } finally { setSaving(false) } }
  function openGrant() { const plan = plans.find((item) => item.status === 1); grantForm.setFieldsValue({ userId: users[0]?.id, planId: plan?.id, expiresAt: dayjs().add(1, 'year') }); setGrantOpen(true) }
  function openSubscription() { subscriptionForm.setFieldsValue({ userId: users[0]?.id }); setSubscriptionOpen(true) }
  async function searchUsers(value: string) { userSearchController.current?.abort(); const controller = new AbortController(); userSearchController.current = controller; const sequence = ++userSearchSequence.current; try { const result = await listUsers(value.trim(), 1, 20, { signal: controller.signal }); if (mounted.current && !controller.signal.aborted && sequence === userSearchSequence.current) setUsers(result.list) } catch (reason) { if (mounted.current && !controller.signal.aborted && sequence === userSearchSequence.current) setError(adminErrorMessage(reason, '用户搜索失败')) } }
  async function confirmGrant() { const input = await grantForm.validateFields(); setSaving(true); setNotice(''); setError(''); try { await grantSubscription(input.userId, input.planId, input.expiresAt.toISOString()); message.success('授权成功'); setNotice('授权成功'); setGrantOpen(false) } catch (reason) { setError(adminErrorMessage(reason, '套餐授权失败')) } finally { setSaving(false) } }
  async function changeSubscription(action: 'pause' | 'cancel') {
    const { userId } = await subscriptionForm.validateFields()
    const isPause = action === 'pause'
    Modal.confirm({
      title: isPause ? '确认暂停该用户订阅？' : '确认取消该用户订阅？',
      content: isPause ? '暂停后用户将回到基础套餐。' : '取消后用户将回到基础套餐。',
      okText: isPause ? '确认暂停' : '确认取消',
      cancelText: '返回',
      okButtonProps: { danger: true },
      onOk: async () => {
        setSaving(true); setNotice(''); setError('')
        try {
          if (isPause) await pauseSubscription(userId); else await cancelSubscription(userId)
          const successText = isPause ? '订阅已暂停' : '订阅已取消'
          message.success(successText); setNotice(successText); setSubscriptionOpen(false)
        } catch (reason) {
          const failure = adminErrorMessage(reason, isPause ? '订阅暂停失败' : '订阅取消失败')
          setError(failure)
          throw new Error(failure)
        } finally { setSaving(false) }
      },
    })
  }
  async function disable(plan: CompanionPlan) { try { await updatePlan(plan.id, { ...plan, status: 0 }); message.success('套餐已停用'); await load() } catch (reason) { throw new Error(reportAdminError(reason, '套餐停用失败')) } }
  return <AdminPage title="套餐管理" loading={loading} error={error} onSearch={(value) => { setQuery(value.trim()); setPage(1) }} actions={<Space wrap><Button onClick={() => openPlan(null)}>新建套餐</Button><Button onClick={openSubscription} disabled={!users.length}>管理用户订阅</Button><Button type="primary" onClick={openGrant} disabled={!plans.length}>授权套餐</Button></Space>}>{notice && <Alert type="success" showIcon message={notice} style={{ marginBottom: 16 }} />}<Table rowKey="id" dataSource={plans} scroll={{ x: 820 }} pagination={{ current: page, total, pageSize: 20, showSizeChanger: false, onChange: setPage }} columns={[
    { title: '套餐', render: (_, row) => <><strong>{row.planName}</strong><div>{row.planCode}</div></> }, { title: '设备', dataIndex: 'maxDevices' }, { title: '角色', dataIndex: 'maxProfiles' }, { title: '长期记忆', render: (_, row) => row.longTermMemory === 1 ? '支持' : '不支持' }, { title: '高级音色', render: (_, row) => row.advancedVoice === 1 ? '支持' : '不支持' }, { title: '状态', render: (_, row) => <Tag color={row.status === 1 ? 'green' : 'default'}>{row.status === 1 ? '启用' : '停用'}</Tag> }, { title: '操作', render: (_, row) => <Space><Button onClick={() => openPlan(row)}>编辑</Button><Button danger disabled={row.status !== 1 || row.planCode === 'basic'} onClick={() => Modal.confirm({ title: '确认停用该套餐？', okText: '确认停用', cancelText: '取消', okButtonProps: { danger: true }, onOk: () => disable(row) })}>停用</Button><Button danger disabled={row.planCode === 'basic'} onClick={() => Modal.confirm({ title: '确认删除该套餐？', okText: '确认删除', cancelText: '取消', okButtonProps: { danger: true }, onOk: async () => { try { await deletePlan(row.id); await load() } catch (reason) { throw new Error(reportAdminError(reason, '套餐删除失败')) } } })}>删除</Button></Space> },
  ]} />
    <Modal title={editing ? '编辑套餐' : '新建套餐'} open={editing !== undefined} onCancel={() => setEditing(undefined)} onOk={savePlan} confirmLoading={saving} okText="保存" destroyOnHidden><Form form={planForm} layout="vertical"><Form.Item name="planCode" label="套餐代码" rules={[{ required: true }]}><Input disabled={editing?.planCode === 'basic'} /></Form.Item><Form.Item name="planName" label="套餐名称" rules={[{ required: true }]}><Input /></Form.Item><Space wrap><Form.Item name="maxDevices" label="设备上限" rules={[{ required: true }]}><InputNumber min={1} /></Form.Item><Form.Item name="maxProfiles" label="角色上限" rules={[{ required: true }]}><InputNumber min={1} /></Form.Item></Space><Form.Item name="longTermMemory" label="长期记忆" valuePropName="checked" getValueFromEvent={(checked) => checked ? 1 : 0} getValueProps={(value) => ({ checked: value === 1 })}><Switch /></Form.Item><Form.Item name="advancedVoice" label="高级音色" valuePropName="checked" getValueFromEvent={(checked) => checked ? 1 : 0} getValueProps={(value) => ({ checked: value === 1 })}><Switch /></Form.Item><Form.Item name="status" hidden><InputNumber /></Form.Item></Form></Modal>
    <Modal title="授权套餐" open={grantOpen} onCancel={() => setGrantOpen(false)} onOk={confirmGrant} confirmLoading={saving} okText="确认授权" cancelText="取消" destroyOnHidden><Form form={grantForm} layout="vertical"><Form.Item name="userId" label="用户" rules={[{ required: true }]}><Select showSearch filterOption={false} onSearch={(value) => void searchUsers(value)} options={users.map((user) => ({ value: user.id, label: user.username || user.mobile || user.id }))} /></Form.Item><Form.Item name="planId" label="套餐" rules={[{ required: true }]}><Select options={plans.filter((plan) => plan.status === 1).map((plan) => ({ value: plan.id, label: plan.planName }))} /></Form.Item><Form.Item name="expiresAt" label="到期时间" rules={[{ required: true }]}><DatePicker showTime style={{ width: '100%' }} disabledDate={(date) => date.isBefore(dayjs(), 'day')} /></Form.Item></Form></Modal>
    <Modal title="管理用户订阅" open={subscriptionOpen} onCancel={() => setSubscriptionOpen(false)} footer={<Space><Button onClick={() => setSubscriptionOpen(false)}>关闭</Button><Button danger loading={saving} onClick={() => void changeSubscription('pause')}>暂停订阅</Button><Button danger type="primary" loading={saving} onClick={() => void changeSubscription('cancel')}>取消订阅</Button></Space>} destroyOnHidden><Form form={subscriptionForm} layout="vertical"><Form.Item name="userId" label="用户" rules={[{ required: true }]}><Select showSearch filterOption={false} onSearch={(value) => void searchUsers(value)} options={users.map((user) => ({ value: user.id, label: user.username || user.mobile || user.id }))} /></Form.Item></Form></Modal>
  </AdminPage>
}
