import { Alert, Card } from 'antd'

import type { ProfileModelBinding, ProfileModelOption } from '../../../api/profiles'
import { ProfileModelSettings } from '../ProfileModelSettings'

type ProfileModelsTabProps = {
  modelBindings: ProfileModelBinding[]
  modelOptions: ProfileModelOption[]
  modelSaveError: string
  onChangeModels: (next: ProfileModelBinding[], changed: ProfileModelBinding) => void
  onCredentialsChanged?: () => void
}

export function ProfileModelsTab({ modelBindings, modelOptions, modelSaveError, onChangeModels, onCredentialsChanged }: ProfileModelsTabProps) {
  return <Card className="surface-card profile-model-card" title="AI 模型">
    {modelSaveError && <Alert type="warning" showIcon message={modelSaveError} />}
    <ProfileModelSettings value={modelBindings} options={modelOptions} onChange={onChangeModels} onCredentialsChanged={onCredentialsChanged} />
  </Card>
}
