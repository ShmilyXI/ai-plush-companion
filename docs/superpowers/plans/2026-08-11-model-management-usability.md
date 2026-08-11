# Model Management Usability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move model connection-test feedback onto the test button, show model Key readiness in the management list, stabilize the TTS voice column, and hide unconfigured models from companion-role selectors.

**Architecture:** Keep the change inside `companion-console`. A focused credential helper will share the existing password/key detection rule between the editor and list status calculation. Model management will load provider metadata alongside rows, while profile selection will filter the already-validated model options without changing the API contract.

**Tech Stack:** React 19, TypeScript, Ant Design 5, Ant Design ProTable, Vitest, Testing Library

---

### Task 1: Shared credential status calculation

**Files:**
- Create: `server/main/companion-console/src/pages/models/modelCredentials.ts`
- Create: `server/main/companion-console/src/pages/models/modelCredentials.test.ts`
- Modify: `server/main/companion-console/src/pages/models/ModelFieldEditor.tsx`

- [ ] **Step 1: Write the failing credential-status tests**

```ts
import { describe, expect, it } from 'vitest'
import type { ModelConfig, ModelProvider } from '../../api/xiaozhiModels'
import { credentialStatus, isCredentialField } from './modelCredentials'

const provider = (fields: ModelProvider['fields']): ModelProvider => ({
  id: 'provider', modelType: 'LLM', providerCode: 'openai', name: 'OpenAI', fields,
  sort: 1, updater: null, updateDate: null, creator: null, createDate: null,
})
const model = (paths: string[]): ModelConfig => ({
  id: 'model', modelType: 'LLM', modelCode: 'model', modelName: 'Model',
  isDefault: 0, isEnabled: 1, configJson: { type: 'openai' }, docLink: null,
  remark: null, sort: 1, configuredSecretPaths: paths,
})

describe('modelCredentials', () => {
  it('recognizes password fields and credential-like keys', () => {
    expect(isCredentialField({ key: 'password', type: 'string' })).toBe(true)
    expect(isCredentialField({ key: 'apiKey', type: 'string' })).toBe(true)
    expect(isCredentialField({ key: 'base_url', type: 'string' })).toBe(false)
  })

  it('returns not_required when the provider has no credential fields', () => {
    expect(credentialStatus(model([]), provider([{ key: 'base_url', label: '地址', type: 'string' }]))).toBe('not_required')
  })

  it('requires every credential field to be configured', () => {
    const secured = provider([
      { key: 'api_key', label: 'API Key', type: 'string' },
      { key: 'secret', label: 'Secret', type: 'password' },
    ])
    expect(credentialStatus(model(['api_key']), secured)).toBe('missing')
    expect(credentialStatus(model(['api_key', 'secret']), secured)).toBe('configured')
  })
})
```

- [ ] **Step 2: Run the test and verify RED**

Run: `cd server/main/companion-console && npm test -- src/pages/models/modelCredentials.test.ts`

Expected: FAIL because `./modelCredentials` does not exist.

- [ ] **Step 3: Implement the helper and reuse it in the field editor**

```ts
import type { ModelConfig, ModelProvider, ModelProviderField } from '../../api/xiaozhiModels'

export type ModelCredentialStatus = 'configured' | 'missing' | 'not_required'

export function isCredentialField(field: Pick<ModelProviderField, 'key' | 'type'>) {
  const normalized = field.key.replace(/([a-z0-9])([A-Z])/g, '$1_$2').replace(/[^a-zA-Z0-9]+/g, '_').toLowerCase()
  return field.type === 'password'
    || /(^|_)(token|secret|password|authorization|credential)($|_)/.test(normalized)
    || normalized.includes('api_key')
    || normalized.includes('access_key_secret')
    || normalized.includes('private_key')
}

export function credentialStatus(model: ModelConfig, provider?: ModelProvider): ModelCredentialStatus {
  const credentialFields = provider?.fields.filter(isCredentialField) ?? []
  if (credentialFields.length === 0) return provider ? 'not_required' : 'missing'
  const configured = new Set(model.configuredSecretPaths)
  return credentialFields.every((field) => configured.has(field.key)) ? 'configured' : 'missing'
}
```

Delete the private `isCredentialKey` function from `ModelFieldEditor.tsx`, import `isCredentialField`, and replace the local credential expression with `const credential = isCredentialField(field)`.

- [ ] **Step 4: Run helper and field-editor tests and verify GREEN**

Run: `cd server/main/companion-console && npm test -- src/pages/models/modelCredentials.test.ts src/pages/models/ModelFieldEditor.test.tsx`

Expected: both files pass.

- [ ] **Step 5: Commit the helper**

```bash
git add server/main/companion-console/src/pages/models/modelCredentials.ts server/main/companion-console/src/pages/models/modelCredentials.test.ts server/main/companion-console/src/pages/models/ModelFieldEditor.tsx
git commit -m "refactor(console): share model credential detection"
```

### Task 2: Model list status, test-button feedback, and TTS voice column

**Files:**
- Modify: `server/main/companion-console/src/pages/models/ModelManagementPage.test.tsx`
- Modify: `server/main/companion-console/src/pages/models/ModelManagementPage.tsx`

- [ ] **Step 1: Write failing component tests**

Extend provider fixtures with an `edge` TTS provider that has no credential fields and return providers by requested type:

```ts
const ttsProviders: modelApi.ModelProvider[] = [{
  id: 'SYSTEM_TTS_edge', modelType: 'TTS', providerCode: 'edge', name: 'Edge TTS', fields: [],
  sort: 1, updater: null, updateDate: null, creator: null, createDate: null,
}]

vi.mocked(modelApi.listProviderTypes).mockImplementation(async (modelType) => modelType === 'TTS' ? ttsProviders : providers)
```

Add a second LLM row without configured secrets and assert all three statuses:

```ts
vi.mocked(modelApi.listModelConfigs).mockResolvedValueOnce({ total: 2, list: [llmModel, secondLlmModel] })
renderPage()
expect((await screen.findAllByText('已配置')).length).toBeGreaterThan(0)
expect(screen.getByText('未配置')).toBeInTheDocument()

await userEvent.click(screen.getByRole('tab', { name: '语音合成 TTS' }))
expect(await screen.findByText('无需配置')).toBeInTheDocument()
```

Change the connection test assertion to:

```ts
expect(await within(drawer).findByRole('button', { name: '测试成功 · 18 ms' })).toBeEnabled()
expect(within(drawer).queryByRole('alert')).not.toBeInTheDocument()
```

Add a rejected test result assertion:

```ts
vi.mocked(modelApi.testModelConfig).mockResolvedValueOnce({ success: false, elapsedMillis: 12, message: '密钥无效' })
const user = userEvent.setup()
renderPage()
await user.click(await screen.findByRole('button', { name: '编辑 深度求索' }))
const drawer = await screen.findByRole('dialog', { name: '编辑模型' })
await user.click(within(drawer).getByRole('button', { name: '测试连接' }))
expect(await within(drawer).findByRole('button', { name: '测试失败' })).toHaveAttribute('title', '密钥无效')
```

Change the stale-result test to assert that editing a field restores a `测试连接` button. In the TTS link test add:

```ts
expect(screen.getByRole('columnheader', { name: '音色' })).toHaveStyle({ width: '96px' })
expect(screen.getByRole('link', { name: '管理音色' })).toHaveStyle({ whiteSpace: 'nowrap' })
```

- [ ] **Step 2: Run the page test and verify RED**

Run: `cd server/main/companion-console && npm test -- src/pages/models/ModelManagementPage.test.tsx`

Expected: FAIL because the status column is absent, test feedback still uses an alert, and the voice column is not 96 px with nowrap content.

- [ ] **Step 3: Implement provider loading and Key status tags**

Import `credentialStatus` and add a provider map state/ref for the current model type. Load `listProviderTypes(modelType)` together with `listModelConfigs(...)`, cache successful provider lists by model type, and retain the existing request revision guard. Add a `Key 状态` column whose renderer finds the row provider and maps status to Ant Design tags:

```tsx
const credentialTags = {
  configured: { color: 'success', text: '已配置' },
  missing: { color: 'error', text: '未配置' },
  not_required: { color: 'default', text: '无需配置' },
} as const

render: (_, row) => {
  const status = credentialStatus(row, providerMap.get(providerCode(row)))
  const tag = credentialTags[status]
  return <Tag color={tag.color}>{tag.text}</Tag>
}
```

Reuse `isCredentialField` inside `ModelManagementPage.tsx` and remove its duplicate `isCredentialKey` helper.

- [ ] **Step 4: Move test feedback onto the button and fix the voice column**

Remove the `testResult` alert above the form. Derive button content and title:

```tsx
const testButtonText = testing ? '测试中'
  : testResult?.success ? `测试成功 · ${testResult.elapsedMillis} ms`
    : testResult ? '测试失败' : '测试连接'
const testButtonTitle = testResult && !testResult.success ? testResult.message : undefined
```

Render these values on the footer button, preserving loading, disabled state, click behavior, and `danger={testResult?.success === false}`. Set the TTS voice column to `width: 96`, `align: 'center'`, and render its link with `style={{ whiteSpace: 'nowrap' }}`. Increase the TTS horizontal scroll width only if the new status column needs it.

- [ ] **Step 5: Run the page test and verify GREEN**

Run: `cd server/main/companion-console && npm test -- src/pages/models/ModelManagementPage.test.tsx`

Expected: all tests in the file pass.

- [ ] **Step 6: Commit model management changes**

```bash
git add server/main/companion-console/src/pages/models/ModelManagementPage.tsx server/main/companion-console/src/pages/models/ModelManagementPage.test.tsx
git commit -m "feat(console): improve model configuration status"
```

### Task 3: Filter companion-role model choices

**Files:**
- Modify: `server/main/companion-console/src/pages/profiles/ProfileModelSettings.test.tsx`
- Modify: `server/main/companion-console/src/pages/profiles/ProfileModelSettings.tsx`

- [ ] **Step 1: Replace the old missing-credential visibility test**

```ts
it('only offers enabled models with configured or unnecessary credentials', async () => {
  renderSettings()

  await userEvent.click(screen.getByRole('combobox', { name: '对话模型 LLM' }))
  expect(screen.queryByText(/DeepSeek/)).not.toBeInTheDocument()
})
```

Add a historical-binding test:

```ts
it('keeps a bound unconfigured model visible only as the disabled current value', async () => {
  renderSettings([
    { modelType: 'LLM', source: 'global', resourceId: 'llm-missing', name: 'DeepSeek',
      overrides: {}, enabled: false, unavailableReason: '请先在模型管理中配置凭据' },
  ])

  expect(screen.getByRole('alert')).toHaveTextContent('请先在模型管理中配置凭据')
  await userEvent.click(screen.getByRole('combobox', { name: '对话模型 LLM' }))
  const current = screen.getByText('DeepSeek · DeepSeek · 未配置')
  expect(current.closest('.ant-select-item-option')).toHaveClass('ant-select-item-option-disabled')
  expect(screen.getAllByText(/DeepSeek/)).toHaveLength(1)
})
```

- [ ] **Step 2: Run the profile settings test and verify RED**

Run: `cd server/main/companion-console && npm test -- src/pages/profiles/ProfileModelSettings.test.tsx`

Expected: FAIL because the missing-credential model is still included in the normal system-model options.

- [ ] **Step 3: Filter selectable options while preserving historical bindings**

Build `typeOptions` from all global options for the model type. Build `selectableOptions` with:

```ts
const selectableOptions = typeOptions.filter((item) => item.enabled
  && (item.credentialStatus === 'configured' || item.credentialStatus === 'not_required'))
```

Find the current `selectedOption` from `typeOptions`, not the filtered array. If the binding is unavailable and its selected value is absent from `selectableOptions`, prepend one disabled option using the selected model label. Render the system-model group from `selectableOptions` only. Keep the existing warning and administrator guidance unchanged.

- [ ] **Step 4: Run the profile settings test and verify GREEN**

Run: `cd server/main/companion-console && npm test -- src/pages/profiles/ProfileModelSettings.test.tsx`

Expected: all tests in the file pass, including historical unavailable-model guidance.

- [ ] **Step 5: Commit profile filtering**

```bash
git add server/main/companion-console/src/pages/profiles/ProfileModelSettings.tsx server/main/companion-console/src/pages/profiles/ProfileModelSettings.test.tsx
git commit -m "fix(console): hide unconfigured role models"
```

### Task 4: Full frontend verification

**Files:**
- Verify: `server/main/companion-console/src/pages/models/modelCredentials.test.ts`
- Verify: `server/main/companion-console/src/pages/models/ModelFieldEditor.test.tsx`
- Verify: `server/main/companion-console/src/pages/models/ModelManagementPage.test.tsx`
- Verify: `server/main/companion-console/src/pages/profiles/ProfileModelSettings.test.tsx`

- [ ] **Step 1: Run focused regression tests**

Run: `cd server/main/companion-console && npm test -- src/pages/models/modelCredentials.test.ts src/pages/models/ModelFieldEditor.test.tsx src/pages/models/ModelManagementPage.test.tsx src/pages/profiles/ProfileModelSettings.test.tsx`

Expected: all focused tests pass with zero failures.

- [ ] **Step 2: Run the complete console test suite**

Run: `cd server/main/companion-console && npm test`

Expected: all tests pass with zero failures.

- [ ] **Step 3: Run lint and production build**

Run: `cd server/main/companion-console && npm run lint && npm run build`

Expected: ESLint exits 0, TypeScript compilation succeeds, Vite builds successfully, and build verification exits 0.

- [ ] **Step 4: Inspect the final diff**

Run: `git diff --check HEAD~3..HEAD && git status --short`

Expected: no whitespace errors; only unrelated pre-existing user changes remain unstaged.
