import { InboxOutlined } from '@ant-design/icons'
import { Alert, Button, Descriptions, Modal, Space, Tag, Typography, Upload } from 'antd'
import { useEffect, useState } from 'react'

import { importSkillPackage, type SkillPackageImport } from '../../api/capabilities'
import { adminErrorMessage } from './adminErrors'

const { Dragger } = Upload

export function SkillPackageImportModal({ open, onCancel, onImported, onFileSelected, onComplete }: {
  open: boolean
  onCancel: () => void
  onImported: (result: SkillPackageImport) => void
  onFileSelected?: (result: SkillPackageImport, file: File) => void
  onComplete?: (result: SkillPackageImport) => void
}) {
  const [result, setResult] = useState<SkillPackageImport | null>(null)
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (open) {
      setResult(null)
      setSelectedFile(null)
      setError('')
    }
  }, [open])

  async function inspect(file: File) {
    setLoading(true)
    setError('')
    try {
      const imported = await importSkillPackage(file)
      setResult(imported)
      setSelectedFile(file)
      onImported(imported)
    } catch (reason) {
      setResult(null)
      setSelectedFile(null)
      setError(adminErrorMessage(reason, 'Skill 包导入失败'))
    } finally {
      setLoading(false)
    }
  }

  return <Modal title="导入 Skill 包" open={open} onCancel={onCancel} footer={null} destroyOnHidden>
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Dragger aria-label="Skill 包" accept=".skill.zip,.zip" multiple={false} showUploadList={false}
        disabled={loading} beforeUpload={(file) => { void inspect(file); return Upload.LIST_IGNORE }}>
        <p className="ant-upload-drag-icon"><InboxOutlined /></p>
        <p className="ant-upload-text">选择或拖入 `.skill.zip`</p>
        <p className="ant-upload-hint">服务端只接受 skill.yaml、SKILL.md 和声明的静态资源。</p>
      </Dragger>
      {loading && <Alert type="info" showIcon message="正在解析并校验 Skill 包" />}
      {error && <Alert type="error" showIcon message={error} />}
      {result && <Space direction="vertical" size="small" style={{ width: '100%' }}>
        <Alert type={result.validation.status === 'VALID' ? 'success' : 'warning'} showIcon
          message={result.validation.status === 'VALID' ? '校验通过' : '需要补全配置'} />
        <Descriptions size="small" column={1} bordered>
          <Descriptions.Item label="Skill">{result.name ?? '未命名'}</Descriptions.Item>
          <Descriptions.Item label="标识">{result.capabilityId ?? '待补齐'}</Descriptions.Item>
          {result.version && <Descriptions.Item label="版本">v{result.version}</Descriptions.Item>}
          {result.packageSha256 && <Descriptions.Item label="SHA-256"><Typography.Text copyable>{result.packageSha256}</Typography.Text></Descriptions.Item>}
        </Descriptions>
        {Array.isArray(result.manifest.tools) && result.manifest.tools.length > 0 && <Space wrap>
          <Typography.Text strong>工具依赖</Typography.Text>
          {result.manifest.tools.map((raw, index) => {
            if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
            const tool = raw as Record<string, unknown>
            return <Tag key={`${String(tool.type)}:${String(tool.ref)}:${String(tool.name)}:${index}`}>
              {[tool.type, tool.ref, tool.name].filter((value) => typeof value === 'string').join(' / ')}
            </Tag>
          })}
        </Space>}
        {result.validation.issues.map((issue) => <Alert key={`${issue.level}:${issue.code}`} type={issue.level === 'ERROR' ? 'error' : 'warning'}
          message={<Space><Tag>{issue.code}</Tag><span>{issue.message}</span></Space>} />)}
        <Typography.Text type="secondary">{result.skillMarkdown.slice(0, 240)}{result.skillMarkdown.length > 240 ? '…' : ''}</Typography.Text>
        {result.validation.status === 'VALID' && selectedFile && onFileSelected
          && <Button type="primary" onClick={() => onFileSelected(result, selectedFile)}>保存为草稿</Button>}
        {result.validation.status === 'INCOMPLETE' && onComplete
          && <Button type="primary" onClick={() => onComplete(result)}>进入补全编辑器</Button>}
      </Space>}
    </Space>
  </Modal>
}
