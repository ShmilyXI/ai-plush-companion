import { Form, Input, Modal } from 'antd'

import type { BindDeviceInput } from '../../api/devices'

interface BindDeviceModalProps {
  open: boolean
  loading: boolean
  onCancel: () => void
  onSubmit: (input: BindDeviceInput) => Promise<void>
}

export function BindDeviceModal({ open, loading, onCancel, onSubmit }: BindDeviceModalProps) {
  const [form] = Form.useForm<BindDeviceInput>()

  async function submit() {
    const values = await form.validateFields()
    await onSubmit(values)
    form.resetFields()
  }

  return (
    <Modal
      title="绑定设备"
      open={open}
      okText="确认绑定"
      cancelText="取消"
      confirmLoading={loading}
      onOk={() => { void submit().catch(() => undefined) }}
      onCancel={() => {
        form.resetFields()
        onCancel()
      }}
      destroyOnHidden
    >
      <p className="modal-help">输入设备屏幕或配网提示中的六位数字。</p>
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item
          name="activationCode"
          label="六位绑定码"
          rules={[{ required: true, pattern: /^\d{6}$/, message: '请输入六位数字绑定码' }]}
        >
          <Input inputMode="numeric" maxLength={6} autoComplete="one-time-code" placeholder="例如 123456" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
