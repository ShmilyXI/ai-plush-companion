import { LockOutlined, RobotOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Form, Input, Space, Typography } from 'antd'
import { useRef, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'

import { AppThemeProvider } from '../app/AppThemeProvider'
import { useAuthStore } from '../auth/authStore'
import { getSafeRedirect } from '../auth/redirect'

interface LoginFormValues {
  username: string
  password: string
}

export function LoginPage() {
  const [loginError, setLoginError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const submissionLock = useRef<symbol | null>(null)
  const status = useAuthStore((state) => state.status)
  const initiallyAuthenticated = useRef(status === 'authenticated').current
  const login = useAuthStore((state) => state.login)
  const navigate = useNavigate()
  const location = useLocation()
  const registeredUsername = new URLSearchParams(location.search).get('registered')?.trim() ?? ''
  const safeRegisteredUsername = registeredUsername.length <= 128 ? registeredUsername : ''

  if (initiallyAuthenticated) return <Navigate to="/dashboard" replace />

  async function submit(values: LoginFormValues) {
    if (submissionLock.current) return
    const submission = Symbol('login submission')
    submissionLock.current = submission
    setSubmitting(true)
    setLoginError('')
    try {
      await login(values)
      navigate(getSafeRedirect(location.search), { replace: true })
    } catch (error) {
      setLoginError(error instanceof Error ? error.message : '登录失败，请稍后重试')
    } finally {
      if (submissionLock.current === submission) {
        submissionLock.current = null
        setSubmitting(false)
      }
    }
  }

  return (
    <AppThemeProvider><main className="login-page">
      <section className="login-intro">
        <div className="login-brand"><RobotOutlined /> 紫萱管理台</div>
        <Typography.Title>让设备、角色与记忆待在同一个温暖空间。</Typography.Title>
        <Typography.Paragraph>
          管理陪伴设备和身份配置。管理员账号会自动获得相应工作区。
        </Typography.Paragraph>
        <Space className="security-note"><SafetyCertificateOutlined />账号凭据经 SM2 加密后提交</Space>
      </section>
      <Card className="login-card" variant="borderless">
        <Typography.Title level={2}>登录</Typography.Title>
        <Typography.Paragraph type="secondary">使用现有紫萱服务端账号</Typography.Paragraph>
        {safeRegisteredUsername && <Alert className="login-alert" type="success" showIcon message="注册成功，请登录" />}
        {loginError && <Alert className="login-alert" type="error" showIcon message={loginError} />}
        <Form<LoginFormValues>
          layout="vertical"
          size="large"
          initialValues={{ username: safeRegisteredUsername }}
          onFinish={submit}
          onValuesChange={() => setLoginError('')}
          requiredMark={false}
        >
          <Form.Item label="账号" name="username" rules={[{ required: true, message: '请输入账号' }]}>
            <Input prefix={<UserOutlined />} autoComplete="username" placeholder="用户名或手机号" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} autoComplete="current-password" placeholder="请输入密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={submitting} block>
            进入管理台
          </Button>
          <div className="login-switch">没有账号？<Link to="/register">立即注册</Link></div>
        </Form>
      </Card>
    </main></AppThemeProvider>
  )
}
