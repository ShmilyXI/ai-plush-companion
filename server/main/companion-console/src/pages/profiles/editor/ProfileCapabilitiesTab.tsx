import { Card, Form, Switch } from 'antd'

export function ProfileCapabilitiesTab() {
  return <Card className="surface-card" title="设备能力">
    <Form.Item label="使用屏幕表情" name="screenExpressionEnabled" valuePropName="checked"><Switch /></Form.Item>
    <Form.Item label="允许相机偏好" name="cameraPreferenceEnabled" valuePropName="checked"><Switch /></Form.Item>
  </Card>
}
