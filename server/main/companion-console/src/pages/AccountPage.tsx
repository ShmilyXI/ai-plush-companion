import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { PageContainer } from '@ant-design/pro-components'
import { Alert, Button, Card, Descriptions, Form, Input, message } from 'antd'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { changePassword } from '../api/account'
import { useAuthStore } from '../auth/authStore'

interface PasswordForm {
  password: string
  newPassword: string
  confirmPassword: string
}

export function AccountPage() {
  const user = useAuthStore((state) => state.user)
  const logout = useAuthStore((state) => state.logout)
  const navigate = useNavigate()
  const [form] = Form.useForm<PasswordForm>()
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  async function submit(values: PasswordForm) {
    setSaving(true)
    setError('')
    try {
      await changePassword({ password: values.password, newPassword: values.newPassword })
      message.success('密码已修改，请重新登录')
      await logout()
      navigate('/login', { replace: true })
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '密码修改失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <PageContainer title={<h1 className="page-container-title">账号资料</h1>} subTitle="查看账号身份和修改登录密码。">
      {error && <Alert type="error" showIcon message={error} />}
      <Card className="surface-card" title={<><UserOutlined /> 账号信息</>}>
        <Descriptions column={1} items={[
          { key: 'username', label: '用户名', children: user?.username || '-' },
          { key: 'role', label: '账号类型', children: user?.superAdmin === 1 ? '管理员' : '普通用户' },
          { key: 'status', label: '账号状态', children: user?.status === 1 ? '正常' : '已停用' },
        ]} />
      </Card>
      <Card className="surface-card" title={<><LockOutlined /> 修改密码</>}>
        <Form form={form} layout="vertical" onFinish={submit} style={{ maxWidth: 520 }}>
          <Form.Item name="password" label="当前密码" rules={[{ required: true, message: '请输入当前密码' }]}>
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Form.Item name="newPassword" label="新密码" rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '新密码至少 6 位' }]}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="confirmPassword" label="确认新密码" dependencies={['newPassword']} rules={[
            { required: true, message: '请再次输入新密码' },
            ({ getFieldValue }) => ({
              validator(_, value) {
                return !value || getFieldValue('newPassword') === value
                  ? Promise.resolve()
                  : Promise.reject(new Error('两次输入的新密码不一致'))
              },
            }),
          ]}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={saving}>修改密码</Button>
        </Form>
      </Card>
    </PageContainer>
  )
}
