# Model Connection Testing and LLM Parameter Guidance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hide model connection tests that cannot produce a trustworthy result, add plain-language LLM parameter guidance and defaults, and make explicitly configured `top_k` reach compatible runtimes.

**Architecture:** Keep editor capability rules in one small TypeScript metadata module shared by default calculation, field rendering, and connection-button visibility. Enforce the same connection-test boundary in the Java service before the generic OpenAI `/models` probe runs. Extend the Python OpenAI-compatible provider with an optional `top_k` extra body field that is absent unless the user explicitly configured it.

**Tech Stack:** React 19, TypeScript 5.9, Ant Design 5, Vitest, Java 21, Spring Boot 3.4, JUnit 5, Python, pytest, OpenAI Python SDK.

---

## File Structure

`server/main/companion-console/src/pages/models/modelEditorMetadata.ts` will own supported connection-test combinations, LLM help text, recommended defaults, and the optional top-k placeholder.

`server/main/companion-console/src/pages/models/modelEditorMetadata.test.ts` will test those pure capability and metadata rules without rendering the full page.

`server/main/companion-console/src/pages/models/ModelFieldEditor.tsx` will render LLM-only help text and the top-k placeholder while leaving same-named TTS fields unchanged.

`server/main/companion-console/src/pages/models/ModelFieldEditor.test.tsx` will cover the visible plain-language copy and its LLM-only scope.

`server/main/companion-console/src/pages/models/ModelManagementPage.tsx` will use recommended defaults for new LLM configurations and only render the connection button when the selected model type and provider are supported.

`server/main/companion-console/src/pages/models/ModelManagementPage.test.tsx` will cover defaults, supported tests, and hidden buttons for TTS, ASR, VAD, Memory, and unsupported LLM providers.

`server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImpl.java` will reject unsupported model types and providers before invoking the generic tester.

`server/main/manager-api/src/test/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImplTest.java` will prove the guard and preserve the existing saved-secret merge behavior.

`server/main/xiaozhi-server/core/providers/llm/openai/openai.py` will parse optional `top_k` and send it through `extra_body` only when configured.

`server/main/xiaozhi-server/tests/test_llm_openai_sampling.py` will verify both the configured and omitted top-k request shapes.

### Task 1: Add model editor capability metadata

**Files:**
- Create: `server/main/companion-console/src/pages/models/modelEditorMetadata.ts`
- Create: `server/main/companion-console/src/pages/models/modelEditorMetadata.test.ts`

- [ ] **Step 1: Write the failing metadata tests**

```ts
import { describe, expect, it } from 'vitest'

import {
  canTestModelConnection,
  llmFieldDefault,
  llmFieldGuidance,
} from './modelEditorMetadata'

describe('modelEditorMetadata', () => {
  it('only enables the generic connection test for OpenAI LLM and VLLM providers', () => {
    expect(canTestModelConnection('LLM', 'openai')).toBe(true)
    expect(canTestModelConnection('VLLM', 'openai')).toBe(true)
    expect(canTestModelConnection('TTS', 'openai')).toBe(false)
    expect(canTestModelConnection('ASR', 'openai')).toBe(false)
    expect(canTestModelConnection('VAD', 'silero')).toBe(false)
    expect(canTestModelConnection('Memory', 'mem0ai')).toBe(false)
    expect(canTestModelConnection('LLM', 'gemini')).toBe(false)
  })

  it('returns recommended defaults only for LLM fields with safe cross-provider values', () => {
    expect(llmFieldDefault('LLM', 'temperature')).toBe(0.7)
    expect(llmFieldDefault('LLM', 'max_tokens')).toBe(2048)
    expect(llmFieldDefault('LLM', 'top_p')).toBe(1)
    expect(llmFieldDefault('LLM', 'frequency_penalty')).toBe(0)
    expect(llmFieldDefault('LLM', 'top_k')).toBeUndefined()
    expect(llmFieldDefault('TTS', 'temperature')).toBeUndefined()
  })

  it('keeps top_k optional and explains the common starting value', () => {
    expect(llmFieldGuidance('LLM', 'top_k')).toEqual(expect.objectContaining({
      placeholder: '常见 40，不确定请留空',
    }))
    expect(llmFieldGuidance('TTS', 'top_k')).toBeUndefined()
  })
})
```

- [ ] **Step 2: Run the tests and verify the module is missing**

Run: `npm test -- --run src/pages/models/modelEditorMetadata.test.ts`

Expected: FAIL because `./modelEditorMetadata` cannot be resolved.

- [ ] **Step 3: Add the metadata module**

```ts
import type { ModelType } from '../../api/xiaozhiModels'

interface LlmFieldGuidance {
  help: string
  defaultValue?: number
  placeholder?: string
}

const LLM_FIELD_GUIDANCE: Record<string, LlmFieldGuidance> = {
  temperature: {
    defaultValue: 0.7,
    help: '控制回答有多活。调低会更稳、更像固定答案；调高会更有变化，也更容易跑偏。建议 0.7。',
  },
  max_tokens: {
    defaultValue: 2048,
    help: '限制一次最多回答多长。调低会更短，太低可能说到一半被截断；调高会更长，也更慢、更费额度。建议 2048。',
  },
  top_p: {
    defaultValue: 1,
    help: '控制模型会考虑多少种说法。调低会更保守；调高会更多样。建议保持 1，通常只调温度，不要两项一起大改。',
  },
  top_k: {
    placeholder: '常见 40，不确定请留空',
    help: '只让模型从最有可能的若干个词里选。数值低更集中，数值高更多样。不是所有接口都支持，常见起点是 40，不确定就留空。',
  },
  frequency_penalty: {
    defaultValue: 0,
    help: '减少同一个词反复出现。调高会少重复，太高会显得生硬。建议 0。',
  },
}

export function canTestModelConnection(modelType: ModelType, providerCode?: string) {
  return (modelType === 'LLM' || modelType === 'VLLM') && providerCode?.toLowerCase() === 'openai'
}

export function llmFieldGuidance(modelType: ModelType, key: string) {
  return modelType === 'LLM' ? LLM_FIELD_GUIDANCE[key] : undefined
}

export function llmFieldDefault(modelType: ModelType, key: string) {
  return llmFieldGuidance(modelType, key)?.defaultValue
}
```

- [ ] **Step 4: Run the metadata tests**

Run: `npm test -- --run src/pages/models/modelEditorMetadata.test.ts`

Expected: PASS with 3 tests.

- [ ] **Step 5: Commit the metadata unit**

```bash
git add server/main/companion-console/src/pages/models/modelEditorMetadata.ts server/main/companion-console/src/pages/models/modelEditorMetadata.test.ts
git commit -m "feat(console): define model editor capabilities"
```

### Task 2: Render LLM guidance and recommended defaults

**Files:**
- Modify: `server/main/companion-console/src/pages/models/ModelFieldEditor.tsx`
- Modify: `server/main/companion-console/src/pages/models/ModelFieldEditor.test.tsx`
- Modify: `server/main/companion-console/src/pages/models/ModelManagementPage.tsx`
- Modify: `server/main/companion-console/src/pages/models/ModelManagementPage.test.tsx`

- [ ] **Step 1: Add failing field guidance tests**

Change the test harness to pass `modelType="LLM"`, add all five fields, and add these assertions:

```tsx
function Harness({ modelType = 'LLM' }: { modelType?: modelApi.ModelType }) {
  return <Form initialValues={{ configJson: { temperature: 0.5 } }}>
    <ModelFieldEditor
      modelType={modelType}
      fields={fields}
      configuredSecretPaths={new Set(['api_key'])}
    />
  </Form>
}

it('explains LLM sampling controls in plain language', () => {
  render(<Harness />)

  expect(screen.getByText(/调低会更稳/)).toBeInTheDocument()
  expect(screen.getByText(/太低可能说到一半被截断/)).toBeInTheDocument()
  expect(screen.getByText(/通常只调温度/)).toBeInTheDocument()
  expect(screen.getByText(/常见起点是 40/)).toBeInTheDocument()
  expect(screen.getByText(/建议 0/)).toBeInTheDocument()
  expect(screen.getByLabelText('top_k值')).toHaveAttribute('placeholder', '常见 40，不确定请留空')
})

it('does not reuse LLM guidance for TTS fields with the same names', () => {
  render(<Harness modelType="TTS" />)

  expect(screen.queryByText(/调低会更稳/)).not.toBeInTheDocument()
  expect(screen.getByLabelText('top_k值')).not.toHaveAttribute('placeholder')
})
```

- [ ] **Step 2: Add failing page behavior tests**

Extend the mocked OpenAI provider with `max_tokens`, `top_p`, `top_k`, and `frequency_penalty` fields that do not define schema defaults. Add these tests:

```tsx
it('fills safe recommended defaults for a new LLM model while leaving top_k empty', async () => {
  const user = userEvent.setup()
  renderPage()

  await user.click(await screen.findByRole('button', { name: '新增模型' }))
  const drawer = await screen.findByRole('dialog', { name: '新增模型' })

  expect(within(drawer).getByLabelText('温度')).toHaveValue(0.7)
  expect(within(drawer).getByLabelText('最大令牌数')).toHaveValue(2048)
  expect(within(drawer).getByLabelText('top_p值')).toHaveValue(1)
  expect(within(drawer).getByLabelText('top_k值')).toHaveValue(null)
  expect(within(drawer).getByLabelText('频率惩罚')).toHaveValue(0)
})

it.each([
  ['TTS', '语音合成 TTS'],
  ['ASR', '语音识别 ASR'],
  ['VAD', '语音活动检测 VAD'],
  ['Memory', '记忆模型 Memory'],
] as const)('hides connection testing for %s models', async (_, tabName) => {
  vi.mocked(modelApi.listProviderTypes).mockImplementation(async (modelType) => [{
    ...providers[0],
    id: `SYSTEM_${modelType}_test`,
    modelType,
    providerCode: modelType === 'VAD' ? 'silero' : 'openai',
    fields: [],
  }])
  const user = userEvent.setup()
  renderPage()

  await user.click(screen.getByRole('tab', { name: tabName }))
  await user.click(await screen.findByRole('button', { name: '新增模型' }))
  const drawer = await screen.findByRole('dialog', { name: '新增模型' })

  expect(within(drawer).queryByRole('button', { name: '测试连接' })).not.toBeInTheDocument()
})

it('hides connection testing for a non-OpenAI LLM provider', async () => {
  vi.mocked(modelApi.listProviderTypes).mockResolvedValue([{
    ...providers[0],
    id: 'SYSTEM_LLM_gemini',
    providerCode: 'gemini',
    name: 'Gemini',
  }])
  const user = userEvent.setup()
  renderPage()

  await user.click(await screen.findByRole('button', { name: '新增模型' }))
  const drawer = await screen.findByRole('dialog', { name: '新增模型' })

  expect(within(drawer).queryByRole('button', { name: '测试连接' })).not.toBeInTheDocument()
})
```

- [ ] **Step 3: Run the focused frontend tests and verify the new expectations fail**

Run: `npm test -- --run src/pages/models/ModelFieldEditor.test.tsx src/pages/models/ModelManagementPage.test.tsx`

Expected: FAIL because `ModelFieldEditor` has no `modelType` prop, guidance is absent, defaults are absent, and the test button is still unconditional.

- [ ] **Step 4: Render the guidance in ModelFieldEditor**

Add the model type prop and metadata lookup:

```tsx
import type { ModelProviderField, ModelType } from '../../api/xiaozhiModels'
import { llmFieldGuidance } from './modelEditorMetadata'

interface ModelFieldEditorProps {
  modelType: ModelType
  fields: ModelProviderField[]
  configuredSecretPaths?: Set<string>
}

export function ModelFieldEditor({ modelType, fields, configuredSecretPaths = new Set() }: ModelFieldEditorProps) {
  return <>
    {fields.map((field) => {
      const guidance = llmFieldGuidance(modelType, field.key)
      const credential = field.type === 'password' || isCredentialKey(field.key)
      const savedSecret = credential && configuredSecretPaths.has(field.key)
      const rules = field.type === 'dict' ? [{ validator: dictValidator }] : undefined
      let control
      if (field.options?.length) {
        control = <Select aria-label={field.label} options={field.options.map(option)} />
      } else if (isNumeric(field.type)) {
        control = <InputNumber
          aria-label={field.label}
          placeholder={guidance?.placeholder}
          style={{ width: '100%' }}
        />
      } else if (field.type === 'boolean') {
        control = <Switch aria-label={field.label} />
      } else if (field.type === 'dict') {
        control = <Input.TextArea aria-label={field.label} rows={4} spellCheck={false} />
      } else if (credential) {
        control = <Input.Password aria-label={field.label} autoComplete="new-password" />
      } else {
        control = <Input aria-label={field.label} />
      }
      return <Form.Item
        key={field.key}
        name={['configJson', field.key]}
        label={field.label}
        valuePropName={field.type === 'boolean' ? 'checked' : 'value'}
        rules={rules}
        extra={savedSecret
          ? <Typography.Text type="secondary">{field.label} 已配置，留空会保留原值</Typography.Text>
          : guidance?.help
            ? <Typography.Text type="secondary">{guidance.help}</Typography.Text>
            : undefined}
      >{control}</Form.Item>
    })}
  </>
}
```

- [ ] **Step 5: Apply defaults and connection capability in ModelManagementPage**

Import the metadata helpers and update initial-value calculation:

```tsx
import { canTestModelConnection, llmFieldDefault } from './modelEditorMetadata'

function fieldInitialValue(field: ModelProviderField, value: unknown, modelType: ModelType) {
  if (field.type === 'password' || isCredentialKey(field.key)) return undefined
  if (field.type === 'dict' && value !== undefined && value !== null) return JSON.stringify(value, null, 2)
  return value ?? field.default ?? llmFieldDefault(modelType, field.key)
}

function initialConfig(provider: ModelProvider, model?: ModelConfig) {
  const values: Record<string, NonNullable<unknown> | undefined> = {}
  for (const field of provider.fields) {
    const value = model?.configJson?.[field.key]
    const initial = fieldInitialValue(field, value, provider.modelType)
    if (initial !== undefined) values[field.key] = initial
  }
  return values
}
```

Calculate support before returning JSX:

```tsx
const connectionTestSupported = canTestModelConnection(activeType, selectedProvider?.providerCode)
```

Render the footer button conditionally and pass the model type into the field editor:

```tsx
{connectionTestSupported && <Button
  loading={testing}
  disabled={editorLoading || saving || !selectedProvider}
  onClick={() => void testConnection()}
>测试连接</Button>}

<ModelFieldEditor
  modelType={activeType}
  fields={selectedProvider.fields}
  configuredSecretPaths={new Set(editing?.configuredSecretPaths ?? [])}
/>
```

- [ ] **Step 6: Run the focused frontend tests**

Run: `npm test -- --run src/pages/models/modelEditorMetadata.test.ts src/pages/models/ModelFieldEditor.test.tsx src/pages/models/ModelManagementPage.test.tsx`

Expected: PASS for all focused model editor tests.

- [ ] **Step 7: Commit the frontend behavior**

```bash
git add server/main/companion-console/src/pages/models/ModelFieldEditor.tsx server/main/companion-console/src/pages/models/ModelFieldEditor.test.tsx server/main/companion-console/src/pages/models/ModelManagementPage.tsx server/main/companion-console/src/pages/models/ModelManagementPage.test.tsx
git commit -m "fix(console): show only reliable model tests"
```

### Task 3: Enforce supported connection tests in the Java API

**Files:**
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImplTest.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImpl.java`

- [ ] **Step 1: Add failing service tests**

Add provider fixtures in `setUp()`:

```java
when(modelProviderService.getList("TTS", "openai")).thenReturn(List.of(new ModelProviderDTO()));
when(modelProviderService.getList("LLM", "gemini")).thenReturn(List.of(new ModelProviderDTO()));
```

Add tests that require a safe result and no network tester call:

```java
@Test
void ttsDoesNotUseTheGenericModelsProbe() {
    CompanionModelTestVO result = service.test("TTS", "openai", null,
            body(new JSONObject().set("api_url", "https://api.example/v1/audio/speech")));

    assertFalse(result.isSuccess());
    assertEquals("当前模型不支持自动测试", result.getMessage());
    verify(tester, never()).test(org.mockito.ArgumentMatchers.anyString(), anyMap());
}

@Test
void nonOpenAiLlmDoesNotUseTheGenericModelsProbe() {
    CompanionModelTestVO result = service.test("LLM", "gemini", null,
            body(new JSONObject().set("api_key", "secret")));

    assertFalse(result.isSuccess());
    assertEquals("当前供应器不支持自动测试", result.getMessage());
    verify(tester, never()).test(org.mockito.ArgumentMatchers.anyString(), anyMap());
}
```

Add the missing static import:

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
```

- [ ] **Step 2: Run the service test and verify both new cases fail**

Run: `mvn -q -DskipTests=false -Dtest=ModelConnectionTestServiceImplTest test`

Expected: FAIL because the service still invokes `CompanionModelConnectionTester`.

- [ ] **Step 3: Add the service capability guard**

After confirming the provider exists and before loading saved configuration, add:

```java
if (!isConversationModel(modelType)) {
    return new CompanionModelTestVO(false, 0, "当前模型不支持自动测试");
}
if (!"openai".equalsIgnoreCase(providerCode)) {
    return new CompanionModelTestVO(false, 0, "当前供应器不支持自动测试");
}
```

Add the helper:

```java
private boolean isConversationModel(String modelType) {
    return "LLM".equalsIgnoreCase(modelType) || "VLLM".equalsIgnoreCase(modelType);
}
```

- [ ] **Step 4: Run the Java model connection tests**

Run: `mvn -q -DskipTests=false -Dtest=ModelConnectionTestServiceImplTest,CompanionModelConnectionTesterImplTest test`

Expected: PASS with no tester invocation in unsupported cases and the existing OpenAI `/models` tests unchanged.

- [ ] **Step 5: Commit the API guard**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImpl.java server/main/manager-api/src/test/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImplTest.java
git commit -m "fix(api): reject unsupported model connection tests"
```

### Task 4: Pass explicit top_k to compatible OpenAI-style services

**Files:**
- Create: `server/main/xiaozhi-server/tests/test_llm_openai_sampling.py`
- Modify: `server/main/xiaozhi-server/core/providers/llm/openai/openai.py`

- [ ] **Step 1: Write the failing Python request-shape tests**

```python
from unittest.mock import MagicMock, patch

from core.providers.llm.openai.openai import LLMProvider


def build_provider(openai_client, **config):
    stream = MagicMock()
    stream.__iter__.return_value = iter([])
    openai_client.return_value.chat.completions.create.return_value = stream
    return LLMProvider({
        "model_name": "test-model",
        "base_url": "https://api.example/v1",
        "api_key": "test-key",
        **config,
    })


def consume(provider):
    list(provider.response("session", [{"role": "user", "content": "你好"}]))


@patch("core.providers.llm.openai.openai.openai.OpenAI")
def test_explicit_top_k_is_sent_through_extra_body(openai_client):
    provider = build_provider(openai_client, top_k=40)

    consume(provider)

    request = openai_client.return_value.chat.completions.create.call_args.kwargs
    assert request["extra_body"] == {"top_k": 40}


@patch("core.providers.llm.openai.openai.openai.OpenAI")
def test_omitted_top_k_does_not_add_extra_body(openai_client):
    provider = build_provider(openai_client)

    consume(provider)

    request = openai_client.return_value.chat.completions.create.call_args.kwargs
    assert "extra_body" not in request
```

- [ ] **Step 2: Run the Python test and verify top_k is absent**

Run: `pytest -q tests/test_llm_openai_sampling.py`

Expected: FAIL in `test_explicit_top_k_is_sent_through_extra_body` because `extra_body` is missing.

- [ ] **Step 3: Parse top_k and add it only when configured**

Add top-k parsing after the existing optional parameter loop:

```python
        try:
            top_k = config.get("top_k")
            parsed_top_k = int(top_k) if top_k not in (None, "") else None
            self.top_k = parsed_top_k if parsed_top_k is not None and parsed_top_k > 0 else None
        except (ValueError, TypeError):
            self.top_k = None
```

Add a focused helper next to `_apply_thinking_disabled`:

```python
    def _apply_top_k(self, request_params: dict):
        if self.top_k is not None:
            request_params.setdefault("extra_body", {})["top_k"] = self.top_k
```

Call it in both `response()` and `response_with_functions()` immediately before `_apply_thinking_disabled(request_params)`:

```python
        self._apply_top_k(request_params)
        self._apply_thinking_disabled(request_params)
```

- [ ] **Step 4: Run the Python sampling tests**

Run: `pytest -q tests/test_llm_openai_sampling.py`

Expected: PASS with 2 tests.

- [ ] **Step 5: Commit the runtime behavior**

```bash
git add server/main/xiaozhi-server/core/providers/llm/openai/openai.py server/main/xiaozhi-server/tests/test_llm_openai_sampling.py
git commit -m "feat(server): pass optional top k to llm providers"
```

### Task 5: Run regression verification

**Files:**
- Verify only; do not stage unrelated user changes already present in the worktree.

- [ ] **Step 1: Run the full companion console test suite**

Run: `npm test`

Working directory: `server/main/companion-console`

Expected: all Vitest suites pass with zero failures.

- [ ] **Step 2: Run frontend lint and production build**

Run: `npm run lint`

Working directory: `server/main/companion-console`

Expected: exit code 0 with no ESLint errors.

Run: `npm run build`

Working directory: `server/main/companion-console`

Expected: TypeScript, Vite build, and build verification all exit 0.

- [ ] **Step 3: Run Java connection-test regression coverage**

Run: `mvn -q -DskipTests=false -Dtest=ModelConnectionTestServiceImplTest,CompanionModelConnectionTesterImplTest test`

Working directory: `server/main/manager-api`

Expected: exit code 0.

- [ ] **Step 4: Run Python provider regression coverage**

Run: `pytest -q tests/test_llm_openai_sampling.py`

Working directory: `server/main/xiaozhi-server`

Expected: 2 passed.

- [ ] **Step 5: Inspect only the intended diff**

Run: `git diff --check`

Expected: no whitespace errors.

Run: `git status --short`

Expected: the pre-existing user changes remain untouched; only task files appear in the implementation commits.

- [ ] **Step 6: Record final evidence**

Report the exact passing test, lint, and build commands. State separately if a command cannot run because of an existing environment dependency, and do not claim that check passed.
