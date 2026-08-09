import { LockOutlined, ReloadOutlined, RobotOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Form, Image, Input, Space, Typography } from 'antd'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { AppThemeProvider } from '../app/AppThemeProvider'
import { registerAccount } from '../auth/register'
import { useCaptcha } from '../auth/useCaptcha'

interface RegisterFormValues {
  username: string
  password: string
  confirmPassword: string
  captcha: string
}

export function RegisterPage() {
  const [registerError, setRegisterError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const navigate = useNavigate()
  const { captcha, refresh } = useCaptcha()

  async function submit(values: RegisterFormValues) {
    if (!captcha.id || captcha.loading) return
    setSubmitting(true)
    setRegisterError('')
    try {
      await registerAccount({
        username: values.username,
        password: values.password,
        captcha: values.captcha,
        captchaId: captcha.id,
      })
      navigate(`/login?registered=${encodeURIComponent(values.username.trim())}`, { replace: true })
    } catch (error) {
      setRegisterError(error instanceof Error ? error.message : '注册失败，请稍后重试')
      void refresh()
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AppThemeProvider><main className="login-page">
      <section className="login-intro">
        <div className="login-brand"><RobotOutlined /> 陪伴管理台</div>
        <Typography.Title>创建属于你的陪伴空间。</Typography.Title>
        <Typography.Paragraph>注册后可以绑定设备、创建角色并管理陪伴记忆。</Typography.Paragraph>
        <Space className="security-note"><SafetyCertificateOutlined />账号凭据经 SM2 加密后提交</Space>
      </section>
      <Card className="login-card" variant="borderless">
        <Typography.Title level={2}>注册</Typography.Title>
        <Typography.Paragraph type="secondary">创建普通用户账号</Typography.Paragraph>
        {registerError && <Alert className="login-alert" type="error" showIcon message={registerError} />}
        {captcha.error && <Alert className="login-alert" type="error" showIcon message={captcha.error} />}
        <Form<RegisterFormValues>
          layout="vertical"
          size="large"
          onFinish={submit}
          onValuesChange={() => setRegisterError('')}
          requiredMark={false}
        >
          <Form.Item label="账号" name="username" rules={[{ required: true, message: '请输入账号' }]}>
            <Input prefix={<UserOutlined />} autoComplete="username" placeholder="用户名" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} autoComplete="new-password" placeholder="请输入密码" />
          </Form.Item>
          <Form.Item
            label="确认密码"
            name="confirmPassword"
            dependencies={['password']}
            rules={[
              { required: true, message: '请再次输入密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('password') === value) return Promise.resolve()
                  return Promise.reject(new Error('两次输入的密码不一致'))
                },
              }),
            ]}
          >
            <Input.Password prefix={<LockOutlined />} autoComplete="new-password" placeholder="再次输入密码" />
          </Form.Item>
          <Form.Item label="验证码" required>
            <Space.Compact block>
              <Form.Item name="captcha" noStyle rules={[{ required: true, len: 5, message: '请输入五位验证码' }]}>
                <Input maxLength={5} autoComplete="off" placeholder="五位验证码" />
              </Form.Item>
              <Button className="captcha-button" aria-label="刷新验证码" aria-busy={captcha.loading} onClick={() => void refresh()}>
                {captcha.url ? <Image preview={false} src={captcha.url} alt="验证码" /> : <ReloadOutlined />}
              </Button>
            </Space.Compact>
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={submitting} disabled={captcha.loading || !captcha.id} block>
            创建账号
          </Button>
          <div className="login-switch">已有账号？<Link to="/login">返回登录</Link></div>
        </Form>
      </Card>
    </main></AppThemeProvider>
  )
}
