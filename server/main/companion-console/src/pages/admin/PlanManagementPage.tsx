import { ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components'
import { Alert, Button, DatePicker, Form, Input, InputNumber, message, Modal, Select, Space, Switch, Tag, type FormInstance } from 'antd'
import dayjs from 'dayjs'
import { useCallback, useEffect, useRef, useState } from 'react'
import { cancelSubscription, createPlan, deletePlan, grantSubscription, listPlans, listUsers, pauseSubscription, updatePlan, type AdminUser, type CompanionPlan, type PlanInput } from '../../api/admin'
import { AdminPage } from './AdminPage'
import { adminErrorMessage, reportAdminError } from './adminErrors'

const emptyPlan: PlanInput = { planCode: '', planName: '', maxDevices: 1, maxProfiles: 1, longTermMemory: 0, advancedVoice: 0, status: 1 }
type ActiveMutation = 'plan' | 'grant' | 'subscription' | 'disable' | 'delete' | null
type GrantInput = { userId: string, planId: string, expiresAt: dayjs.Dayjs }

function GrantFormInitializer({ form, values }: { form: FormInstance<GrantInput>, values: Partial<GrantInput> }) {
  const initialValues = useRef(values)
  useEffect(() => { form.setFieldsValue(initialValues.current) }, [form])
  return null
}

function SubscriptionFormInitializer({ form, userId }: { form: FormInstance<{ userId: string }>, userId?: string }) {
  const initialUserId = useRef(userId)
  useEffect(() => { form.setFieldsValue({ userId: initialUserId.current }) }, [form])
  return null
}

export function PlanManagementPage() {
  const [plans, setPlans] = useState<CompanionPlan[]>([]), [users, setUsers] = useState<AdminUser[]>([]), [tableError, setTableError] = useState(''), [userListError, setUserListError] = useState(''), [planError, setPlanError] = useState(''), [grantError, setGrantError] = useState(''), [subscriptionError, setSubscriptionError] = useState(''), [notice, setNotice] = useState(''), [editing, setEditing] = useState<CompanionPlan | null | undefined>(undefined), [grantOpen, setGrantOpen] = useState(false), [subscriptionOpen, setSubscriptionOpen] = useState(false), [activeMutation, setActiveMutation] = useState<ActiveMutation>(null)
  const [planForm] = Form.useForm<PlanInput>(), [grantForm] = Form.useForm<GrantInput>(), [subscriptionForm] = Form.useForm<{ userId: string }>()
  const actionRef = useRef<ActionType>(null), tableController = useRef<AbortController | null>(null), tableSequence = useRef(0)
  const userSearchController = useRef<AbortController | null>(null), userSearchSequence = useRef(0), mutationBusyRef = useRef(false), mounted = useRef(false), grantDefaults = useRef<Partial<GrantInput>>({})
  const loadInitialUsers = useCallback(async () => {
    userSearchController.current?.abort()
    const controller = new AbortController()
    userSearchController.current = controller
    const sequence = ++userSearchSequence.current
    setUserListError('')
    try {
      const result = await listUsers('', 1, 20, { signal: controller.signal })
      if (mounted.current && !controller.signal.aborted && sequence === userSearchSequence.current) setUsers(result.list)
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted && sequence === userSearchSequence.current) setUserListError(adminErrorMessage(reason, '用户加载失败'))
    }
  }, [])
  useEffect(() => {
    mounted.current = true
    void loadInitialUsers()
    return () => { mounted.current = false; tableSequence.current += 1; tableController.current?.abort(); userSearchController.current?.abort() }
  }, [loadInitialUsers])
  const request = useCallback(async (params: { current?: number; pageSize?: number; keyword?: string }) => {
    const requestId = ++tableSequence.current; tableController.current?.abort(); const controller = new AbortController(); tableController.current = controller; setTableError('')
    try { const result = await listPlans(params.keyword?.trim() || '', params.current ?? 1, params.pageSize ?? 20, { signal: controller.signal }); if (!mounted.current || controller.signal.aborted || tableSequence.current !== requestId) return { data: [], total: 0, success: false }; setPlans(result.list); return { data: result.list, total: result.total, success: true } }
    catch (reason) { if (mounted.current && !controller.signal.aborted && tableSequence.current === requestId) setTableError(reason instanceof Error ? reason.message : '套餐加载失败'); return { data: [], total: 0, success: false } }
  }, [])
  const pending = activeMutation !== null
  function beginMutation(mutation: Exclude<ActiveMutation, null>) { if (mutationBusyRef.current) return false; mutationBusyRef.current = true; setActiveMutation(mutation); return true }
  function finishMutation() { mutationBusyRef.current = false; if (mounted.current) setActiveMutation(null) }
  function openPlan(plan: CompanionPlan | null) { if (pending) return; setPlanError(''); setEditing(plan); planForm.setFieldsValue(plan ? { ...plan } : emptyPlan) }
  function closePlan() { if (pending) return; setPlanError(''); setEditing(undefined) }
  async function savePlan() {
    if (!beginMutation('plan')) return
    let input: PlanInput
    try { input = await planForm.validateFields() } catch { finishMutation(); return }
    setPlanError('')
    try { if (editing) await updatePlan(editing.id, input); else await createPlan(input); message.success(editing ? '套餐已更新' : '套餐已创建'); setEditing(undefined); await actionRef.current?.reload() }
    catch (reason) { if (mounted.current) setPlanError(adminErrorMessage(reason, '套餐保存失败')) }
    finally { finishMutation() }
  }
  function openGrant() { if (pending) return; const plan = plans.find((item) => item.status === 1); setGrantError(''); grantDefaults.current = { userId: users[0]?.id, planId: plan?.id, expiresAt: dayjs().add(1, 'year') }; setGrantOpen(true) }
  function closeGrant() { if (pending) return; setGrantError(''); setGrantOpen(false) }
  function openSubscription() { if (pending) return; setSubscriptionError(''); setSubscriptionOpen(true) }
  function closeSubscription() { if (pending) return; setSubscriptionError(''); subscriptionForm.resetFields(); setSubscriptionOpen(false) }
  async function searchUsers(value: string) { userSearchController.current?.abort(); const controller = new AbortController(); userSearchController.current = controller; const sequence = ++userSearchSequence.current; try { const result = await listUsers(value.trim(), 1, 20, { signal: controller.signal }); if (mounted.current && !controller.signal.aborted && sequence === userSearchSequence.current) setUsers(result.list) } catch (reason) { if (mounted.current && !controller.signal.aborted && sequence === userSearchSequence.current) { const failure = adminErrorMessage(reason, '用户搜索失败'); if (grantOpen) setGrantError(failure); else if (subscriptionOpen) setSubscriptionError(failure); else setUserListError(failure) } } }
  async function confirmGrant() {
    if (!beginMutation('grant')) return
    let input: GrantInput
    try { input = await grantForm.validateFields() } catch { finishMutation(); return }
    setNotice(''); setGrantError('')
    try { await grantSubscription(input.userId, input.planId, input.expiresAt.toISOString()); message.success('授权成功'); setNotice('授权成功'); setGrantOpen(false); await actionRef.current?.reload() }
    catch (reason) { if (mounted.current) setGrantError(adminErrorMessage(reason, '套餐授权失败')) }
    finally { finishMutation() }
  }
  async function changeSubscription(action: 'pause' | 'cancel') {
    if (!beginMutation('subscription')) return
    let userId: string
    try { ({ userId } = await subscriptionForm.validateFields()) } catch { finishMutation(); return }
    const isPause = action === 'pause'
    Modal.confirm({
      title: isPause ? '确认暂停该用户订阅？' : '确认取消该用户订阅？',
      content: isPause ? '暂停后用户将回到基础套餐。' : '取消后用户将回到基础套餐。',
      okText: isPause ? '确认暂停' : '确认取消',
      cancelText: '返回',
      okButtonProps: { danger: true },
      onCancel: finishMutation,
      onOk: async () => {
        setNotice(''); setSubscriptionError('')
        try {
          if (isPause) await pauseSubscription(userId); else await cancelSubscription(userId)
          const successText = isPause ? '订阅已暂停' : '订阅已取消'
          message.success(successText); setNotice(successText); subscriptionForm.resetFields(); setSubscriptionOpen(false); await actionRef.current?.reload()
        } catch (reason) {
          const failure = adminErrorMessage(reason, isPause ? '订阅暂停失败' : '订阅取消失败')
          if (mounted.current) setSubscriptionError(failure)
        } finally { finishMutation() }
      },
    })
  }
  async function disable(plan: CompanionPlan) { if (!beginMutation('disable')) return; try { await updatePlan(plan.id, { ...plan, status: 0 }); message.success('套餐已停用'); await actionRef.current?.reload() } catch (reason) { throw new Error(reportAdminError(reason, '套餐停用失败')) } finally { finishMutation() } }
  async function remove(plan: CompanionPlan) { if (!beginMutation('delete')) return; try { await deletePlan(plan.id); await actionRef.current?.reload() } catch (reason) { throw new Error(reportAdminError(reason, '套餐删除失败')) } finally { finishMutation() } }
  const columns: ProColumns<CompanionPlan>[] = [
    { title: '关键词', dataIndex: 'keyword', hideInTable: true },
    { title: '套餐', hideInSearch: true, render: (_, row) => <><strong>{row.planName}</strong><div>{row.planCode}</div></> }, { title: '设备', dataIndex: 'maxDevices', hideInSearch: true }, { title: '角色', dataIndex: 'maxProfiles', hideInSearch: true }, { title: '长期记忆', hideInSearch: true, render: (_, row) => row.longTermMemory === 1 ? '支持' : '不支持' }, { title: '高级音色', hideInSearch: true, render: (_, row) => row.advancedVoice === 1 ? '支持' : '不支持' }, { title: '状态', hideInSearch: true, render: (_, row) => <Tag color={row.status === 1 ? 'green' : 'default'}>{row.status === 1 ? '启用' : '停用'}</Tag> }, { title: '操作', valueType: 'option', render: (_, row) => <Space><Button disabled={pending} onClick={() => openPlan(row)}>编辑</Button><Button danger disabled={pending || row.status !== 1 || row.planCode === 'basic'} onClick={() => Modal.confirm({ title: '确认停用该套餐？', okText: '确认停用', cancelText: '取消', okButtonProps: { danger: true }, onOk: () => disable(row) })}>停用</Button><Button danger disabled={pending || row.planCode === 'basic'} onClick={() => Modal.confirm({ title: '确认删除该套餐？', okText: '确认删除', cancelText: '取消', okButtonProps: { danger: true }, onOk: () => remove(row) })}>删除</Button></Space> },
  ]
  const pageError = tableError || userListError
  function retryPageLoad() { if (tableError) void actionRef.current?.reload(); if (userListError) void loadInitialUsers() }
  return <AdminPage title="套餐与订阅" error={pageError} onRetry={pageError ? retryPageLoad : undefined} actions={<Space wrap><Button disabled={pending} onClick={() => openPlan(null)}>新建套餐</Button><Button onClick={openSubscription} disabled={pending || !users.length}>管理用户订阅</Button><Button type="primary" onClick={openGrant} disabled={pending || !plans.length}>授权套餐</Button></Space>}>{notice && <Alert type="success" showIcon message={notice} style={{ marginBottom: 16 }} />}<ProTable<CompanionPlan> actionRef={actionRef} rowKey="id" columns={columns} request={request} scroll={{ x: 820 }} pagination={{ defaultPageSize: 20 }} options={false} search={{ labelWidth: 'auto' }} />
    <Modal title={editing ? '编辑套餐' : '新建套餐'} open={editing !== undefined} onCancel={closePlan} onOk={savePlan} confirmLoading={activeMutation === 'plan'} okText="保存" closable={!pending} maskClosable={!pending} cancelButtonProps={{ disabled: pending }} destroyOnHidden>{planError && <Alert type="error" showIcon message={planError} style={{ marginBottom: 16 }} />}<Form form={planForm} layout="vertical"><Form.Item name="planCode" label="套餐代码" rules={[{ required: true }]}><Input disabled={editing?.planCode === 'basic'} /></Form.Item><Form.Item name="planName" label="套餐名称" rules={[{ required: true }]}><Input /></Form.Item><Space wrap><Form.Item name="maxDevices" label="设备上限" rules={[{ required: true }]}><InputNumber min={1} /></Form.Item><Form.Item name="maxProfiles" label="角色上限" rules={[{ required: true }]}><InputNumber min={1} /></Form.Item></Space><Form.Item name="longTermMemory" label="长期记忆" valuePropName="checked" getValueFromEvent={(checked) => checked ? 1 : 0} getValueProps={(value) => ({ checked: value === 1 })}><Switch /></Form.Item><Form.Item name="advancedVoice" label="高级音色" valuePropName="checked" getValueFromEvent={(checked) => checked ? 1 : 0} getValueProps={(value) => ({ checked: value === 1 })}><Switch /></Form.Item><Form.Item name="status" hidden><InputNumber /></Form.Item></Form></Modal>
    <Modal title="授权套餐" open={grantOpen} onCancel={closeGrant} onOk={confirmGrant} confirmLoading={activeMutation === 'grant'} okText="确认授权" cancelText="取消" closable={!pending} maskClosable={!pending} cancelButtonProps={{ disabled: pending }} destroyOnHidden>{grantOpen && <>{grantError && <Alert type="error" showIcon message={grantError} style={{ marginBottom: 16 }} />}<Form form={grantForm} layout="vertical" clearOnDestroy><GrantFormInitializer form={grantForm} values={grantDefaults.current} /><Form.Item name="userId" label="用户" rules={[{ required: true }]}><Select showSearch filterOption={false} onSearch={(value) => void searchUsers(value)} options={users.map((user) => ({ value: user.id, label: user.username || user.mobile || user.id }))} /></Form.Item><Form.Item name="planId" label="套餐" rules={[{ required: true }]}><Select options={plans.filter((plan) => plan.status === 1).map((plan) => ({ value: plan.id, label: plan.planName }))} /></Form.Item><Form.Item name="expiresAt" label="到期时间" rules={[{ required: true }]}><DatePicker showTime style={{ width: '100%' }} disabledDate={(date) => date.isBefore(dayjs(), 'day')} /></Form.Item></Form></>}</Modal>
    <Modal title="管理用户订阅" open={subscriptionOpen} onCancel={closeSubscription} closable={!pending} maskClosable={!pending} footer={<Space><Button disabled={pending} onClick={closeSubscription}>关闭</Button><Button danger disabled={pending} loading={activeMutation === 'subscription'} onClick={() => void changeSubscription('pause')}>暂停订阅</Button><Button danger type="primary" disabled={pending} loading={activeMutation === 'subscription'} onClick={() => void changeSubscription('cancel')}>取消订阅</Button></Space>} destroyOnHidden>{subscriptionOpen && <>{subscriptionError && <Alert type="error" showIcon message={subscriptionError} style={{ marginBottom: 16 }} />}<Form form={subscriptionForm} layout="vertical" clearOnDestroy><SubscriptionFormInitializer form={subscriptionForm} userId={users[0]?.id} /><Form.Item name="userId" label="用户" rules={[{ required: true }]}><Select showSearch filterOption={false} onSearch={(value) => void searchUsers(value)} options={users.map((user) => ({ value: user.id, label: user.username || user.mobile || user.id }))} /></Form.Item></Form></>}</Modal>
  </AdminPage>
}
