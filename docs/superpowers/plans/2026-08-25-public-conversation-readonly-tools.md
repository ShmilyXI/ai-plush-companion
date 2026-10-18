# Public Conversation Readonly Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow public text and audio conversations to call only the published weather and news server plugins bound to the active Agent version, then return grounded text and TTS with observable tool events.

**Architecture:** `manager-api` projects a frozen Skill and tool allowlist into each public runtime bundle. `xiaozhi-server` parses that projection into an isolated public tool runtime, uses the existing LLM function-calling interface, executes only two registered read-only plugins, feeds results back to the same LLM turn, and emits redacted lifecycle events. The browser only renders diagnostics; it never receives tool credentials or executes tools.

**Tech Stack:** Java 21, Spring Boot, MyBatis, JUnit 5 and Mockito; Python 3.12, asyncio, pytest, existing OpenAI-compatible LLM providers and registered server plugins; browser ES modules and Node.js built-in tests.

---

### Task 1: Repair official Skill package publication

**Files:**
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/init/CapabilityBootstrapService.java:63-87`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/init/CapabilityBootstrapServiceTest.java`

- [ ] **Step 1: Write a failing bootstrap regression test**

Add a case proving that a published official Skill without a published package is repaired instead of skipped:

```java
@Test
void republishesOfficialSkillWhenPublishedPackageIsMissing() {
    CapabilityEntity existing = published("skill-weather", "SKILL", 2);
    when(capabilityDao.selectById("skill-weather")).thenReturn(existing);
    when(skillPackages.selectVersion("skill-weather", 2)).thenReturn(null);

    service.initialize();

    verify(capabilities).update(eq(0L), eq("skill-weather"), any(CapabilitySaveDTO.class));
    verify(capabilities).publish(0L, "skill-weather");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd server/main/manager-api
mvn -q -Dtest=CapabilityBootstrapServiceTest#republishesOfficialSkillWhenPublishedPackageIsMissing test
```

Expected: FAIL because `ensure` returns immediately for the published capability.

- [ ] **Step 3: Inject `SkillPackageService` and tighten the early-return condition**

Use this rule in `ensure`:

```java
if (existing != null) {
    if (!type.equals(existing.getType())) throw new IllegalStateException("官方能力 ID 类型冲突: " + id);
    boolean published = "PUBLISHED".equals(existing.getStatus()) && existing.getPublishedVersion() != null;
    boolean packagePresent = !"SKILL".equals(type)
            || skillPackages.selectVersion(id, existing.getPublishedVersion()) != null;
    if (published && packagePresent) return;
}
```

Keep update and publish in the existing transaction so a failed repair does not leave a partial draft.

- [ ] **Step 4: Run the bootstrap test class**

```bash
cd server/main/manager-api
mvn -q -Dtest=CapabilityBootstrapServiceTest test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/init/CapabilityBootstrapService.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/init/CapabilityBootstrapServiceTest.java
git commit -m "fix: repair missing official skill packages"
```

### Task 2: Project an immutable public tool allowlist from Java

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/PublicConversationCapabilityProjection.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/PublicConversationSkillProjectionService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/PublicConversationSkillProjectionServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/PublicConversationServiceImpl.java:130-142`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationSkillProjectionServiceTest.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationServiceTest.java`

- [ ] **Step 1: Replace the old projection expectation with whitelist tests**

Cover these public behaviors:

```java
@Test
void projectsPublishedWeatherToolWithSchemaAndDefaults() {
    // Published active-version binding -> published Skill package -> PLUGIN mapping -> published plugin definition.
    PublicConversationCapabilityProjection projection = service.project("agent-a", 4);
    assertEquals(List.of("get_weather"), projection.skills().get(0).get("toolNames"));
    assertEquals("PLUGIN", projection.tools().get("get_weather").get("type"));
    assertEquals("plugin-weather", projection.tools().get("get_weather").get("refId"));
    assertTrue(((Map<?, ?>) projection.tools().get("get_weather").get("runtime")).containsKey("schema"));
}

@Test
void rejectsPluginOutsidePublicReadonlyAllowlist() {
    PublicConversationCapabilityProjection projection = projectionFor("plugin-web-search", "web_search");
    assertTrue(projection.tools().isEmpty());
    assertEquals(List.of(), projection.skills().get(0).get("toolNames"));
}

@Test
void doesNotProjectSkillWithoutPublishedPackage() {
    when(packages.selectVersion("skill-weather", 2)).thenReturn(null);
    assertTrue(service.project("agent-a", 4).skills().isEmpty());
}
```

- [ ] **Step 2: Run the projection tests and verify RED**

```bash
cd server/main/manager-api
mvn -q -Dtest=PublicConversationSkillProjectionServiceTest test
```

Expected: compilation or assertion failure because the projection record and tools map do not exist.

- [ ] **Step 3: Add the projection record**

```java
public record PublicConversationCapabilityProjection(
        List<Map<String, Object>> skills,
        Map<String, Map<String, Object>> tools) {
    public static PublicConversationCapabilityProjection empty() {
        return new PublicConversationCapabilityProjection(List.of(), Map.of());
    }
}
```

- [ ] **Step 4: Implement the two-tool whitelist**

In `PublicConversationSkillProjectionServiceImpl`, inject `PluginDefinitionDao` and parse the published package's `tools`. Permit only these exact pairs:

```java
private static final Map<String, String> PUBLIC_READONLY_PLUGINS = Map.of(
        "plugin-weather", "get_weather",
        "plugin-news", "get_news_from_newsnow");
```

For every permitted manifest tool, require `type=PLUGIN`, matching `ref`, matching `name`, a published Plugin capability, a matching `executorName`, and a non-empty object `inputSchemaJson`. Project this shape:

```java
tools.put(toolName, Map.of(
    "name", toolName,
    "type", "PLUGIN",
    "refId", pluginId,
    "required", required,
    "defaults", sanitizedDefaults,
    "runtime", Map.of(
        "executor", "SERVER_PLUGIN",
        "schema", Map.of(
            "type", "function",
            "function", Map.of(
                "name", toolName,
                "description", pluginCapability.getDescription(),
                "parameters", inputSchema)))));
```

Remove keys ending in `_secret_id`, `api_key`, `token`, `password`, or `secret` from projected defaults. Set each Skill's `toolNames` to the names actually inserted into the tools map.

- [ ] **Step 5: Put both `skills` and `tools` into the runtime bundle**

In `PublicConversationServiceImpl`:

```java
PublicConversationCapabilityProjection capabilities = skillProjection == null
        ? PublicConversationCapabilityProjection.empty()
        : skillProjection.project(agent.getId(), agent.getActiveVersionNo());
publicConfig.put("skills", capabilities.skills());
publicConfig.put("tools", capabilities.tools());
```

Add a service test asserting both keys survive the Java runtime bundle and no secret field is present.

- [ ] **Step 6: Run Java conversation tests**

```bash
cd server/main/manager-api
mvn -q -Dtest='xiaozhi.modules.conversation.**' test
```

Expected: all conversation tests pass.

- [ ] **Step 7: Commit**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/conversation \
  server/main/manager-api/src/test/java/xiaozhi/modules/conversation
git commit -m "feat: project public readonly conversation tools"
```

### Task 3: Add an isolated Python public tool runtime

**Files:**
- Create: `server/main/xiaozhi-server/core/public_conversation/tools.py`
- Create: `server/main/xiaozhi-server/tests/test_public_conversation_tools.py`

- [ ] **Step 1: Write failing runtime tests**

Use fake registered functions and a minimal bundle to cover selection, execution, rejection, validation, timeout, and result limits:

```python
@pytest.mark.asyncio
async def test_selects_and_executes_only_bound_weather_tool():
    runtime = PublicConversationToolRuntime(bundle(), plugin_registry={"get_weather": weather_item})
    turn = await runtime.select("深圳今天的天气怎么样")
    assert [schema["function"]["name"] for schema in runtime.schemas(turn)] == ["get_weather"]
    result = await runtime.execute(turn, "get_weather", {"location": "深圳"})
    assert "深圳" in result.content

@pytest.mark.asyncio
async def test_rejects_unbound_or_non_whitelisted_tool():
    runtime = PublicConversationToolRuntime(bundle(), plugin_registry={"web_search": search_item})
    with pytest.raises(PublicToolError, match="not allowed"):
        await runtime.execute(await runtime.select("搜索"), "web_search", {"query": "x"})

@pytest.mark.asyncio
async def test_timeout_and_oversized_result_use_stable_codes():
    with pytest.raises(PublicToolError) as timeout:
        await runtime.execute(turn, "get_weather", {"location": "深圳"}, timeout_seconds=0.01)
    assert timeout.value.code == "timeout"
```

- [ ] **Step 2: Run the tests and verify RED**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q tests/test_public_conversation_tools.py
```

Expected: FAIL with `ModuleNotFoundError`.

- [ ] **Step 3: Implement `PublicConversationToolRuntime`**

The module must define:

```python
PUBLIC_READONLY_TOOLS = frozenset({"get_weather", "get_news_from_newsnow"})
MAX_TOOL_CALLS_PER_TURN = 3
MAX_TOOL_RESULT_LENGTH = 32_000

@dataclass(frozen=True)
class PublicToolResult:
    name: str
    content: str
    duration_ms: int

class PublicToolError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
```

Parse `config.skills` and `config.tools` through `CapabilityBundle`. Select the Skill with `SkillTurnRuntime.select`. Return only schemas whose names are in both `turn.allowed_tool_names` and `PUBLIC_READONLY_TOOLS`.

Build a minimal plugin context with `config`, `client_ip=None`, `device_id=conversation_id`, and `last_newsnow_link`. Import `plugins_func.functions` through `auto_import_modules`, resolve the function from `all_function_registry`, validate required fields plus primitive JSON schema types, merge non-secret defaults, then invoke the coroutine with the context because both registered functions use `ToolType.SYSTEM_CTL`.

Convert `ActionResponse.result` or `ActionResponse.response` to bounded text. Map missing tools to `not_allowed`, invalid arguments to `invalid_arguments`, timeout to `timeout`, plugin exceptions to `execution_failed`, and oversized results to `result_too_large`.

- [ ] **Step 4: Run runtime tests**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q tests/test_public_conversation_tools.py
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add server/main/xiaozhi-server/core/public_conversation/tools.py \
  server/main/xiaozhi-server/tests/test_public_conversation_tools.py
git commit -m "feat: add isolated public readonly tool runtime"
```

### Task 4: Add function calling to public conversation turns

**Files:**
- Create: `server/main/xiaozhi-server/core/public_conversation/tool_calls.py`
- Modify: `server/main/xiaozhi-server/core/public_conversation/session.py:21-220`
- Modify: `server/main/xiaozhi-server/core/public_conversation/protocol.py`
- Modify: `server/main/xiaozhi-server/tests/test_public_conversation_http.py`
- Modify: `server/main/xiaozhi-server/tests/test_public_conversation_cross_layer.py`
- Create: `server/main/xiaozhi-server/tests/test_public_conversation_tool_calls.py`

- [ ] **Step 1: Write failing orchestration tests**

Use a fake LLM that emits one tool-call response and then one grounded final response:

```python
class ToolCallingLlm:
    def response_with_functions(self, _session_id, dialogue, functions=None, **_kwargs):
        if not any(message.get("role") == "tool" for message in dialogue):
            yield None, [fake_call("call-1", "get_weather", '{"location":"深圳","lang":"zh_CN"}')]
        else:
            assert "深圳" in dialogue[-1]["content"]
            yield "深圳当前 28 度。", None

@pytest.mark.asyncio
async def test_weather_tool_events_precede_grounded_reply():
    events = await session.handle_text(TextTurnInput("r1", "深圳天气怎么样"))
    assert [item.event_type for item in events] == [
        "turn.started", "tool.started", "tool.completed",
        "llm.delta", "tts.audio", "turn.completed",
    ]
```

Add failure cases for disallowed tool names, invalid JSON arguments, tool timeout, more than three calls, and a provider without `response_with_functions`. In every failure case assert there is no fabricated successful tool result and the WebSocket remains usable.

- [ ] **Step 2: Run orchestration tests and verify RED**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q tests/test_public_conversation_tool_calls.py
```

Expected: FAIL because public sessions call `response` directly and emit no tool events.

- [ ] **Step 3: Implement streamed tool-call assembly**

In `tool_calls.py`, normalize OpenAI objects and dicts into this immutable form and merge streamed argument fragments by call index/id:

```python
@dataclass(frozen=True)
class PublicToolCall:
    id: str
    name: str
    arguments: str
```

Reject blank IDs, blank names, invalid JSON objects, and more than three calls.

- [ ] **Step 4: Add the bounded LLM-tool loop**

Inject `tool_runtime_factory` into `PublicConversationSession`. For each turn:

```python
tool_turn = await tool_runtime.select(text)
schemas = tool_runtime.schemas(tool_turn)
dialogue = self._dialogue(text, memory_text, tool_turn.skill.execution_prompt if tool_turn.skill else None)
```

When `schemas` is empty, keep the current streaming path. Otherwise require `response_with_functions`, collect content and tool-call fragments, execute at most three calls, emit redacted events, append an assistant tool-call message and one `role=tool` result per call, then request the final LLM response. Limit recursion to two tool rounds.

Emit events exactly as follows:

```python
self._next(events, "tool.started", turn_id, {"name": call.name})
self._next(events, "tool.completed", turn_id, {
    "name": result.name, "duration_ms": result.duration_ms,
})
self._next(events, "tool.failed", turn_id, {
    "name": call.name, "code": error.code,
    "message": public_failure_message(error.code),
})
```

Tool results enter only the internal LLM dialogue. Do not put raw result text in event details. A failed tool result must instruct the LLM that current data is unavailable and must not be invented.

- [ ] **Step 5: Preserve cancellation, TTS, memory, and history**

Check cancellation before and after every tool call. Only the final visible LLM response goes to `llm.delta`, TTS, Memory, and history. If the final response is empty, emit the existing `turn_failed` error.

- [ ] **Step 6: Run Python public conversation tests**

```bash
cd server/main/xiaozhi-server
../../../.venv312/bin/python -m pytest -q \
  tests/test_public_conversation_tools.py \
  tests/test_public_conversation_tool_calls.py \
  tests/test_public_conversation_http.py \
  tests/test_public_conversation_cross_layer.py \
  tests/test_public_conversation_protocol.py
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add server/main/xiaozhi-server/core/public_conversation \
  server/main/xiaozhi-server/tests/test_public_conversation_*.py
git commit -m "feat: execute readonly tools in public conversations"
```

### Task 5: Show tool lifecycle in the realtime test tab

**Files:**
- Modify: `docs/api/public-conversation-realtime.js`
- Modify: `docs/api/public-conversation-demo.js`
- Modify: `docs/api/public-conversation-demo.css`
- Modify: `scripts/public-conversation-realtime.test.mjs`

- [ ] **Step 1: Add failing diagnostic tests**

```javascript
test("keeps redacted tool lifecycle details", () => {
  diagnostics.event({ type: "tool.started", turn_id: "t1", details: { name: "get_weather", api_key: "secret" } });
  diagnostics.event({ type: "tool.completed", turn_id: "t1", details: { name: "get_weather", duration_ms: 420 } });
  const tools = diagnostics.snapshot().events.filter((item) => item.type.startsWith("tool."));
  assert.deepEqual(tools.map((item) => [item.type, item.name, item.durationMs]), [
    ["tool.started", "get_weather", null],
    ["tool.completed", "get_weather", 420],
  ]);
  assert.doesNotMatch(JSON.stringify(tools), /secret/);
});
```

- [ ] **Step 2: Run the Node test and verify RED**

```bash
node --test scripts/public-conversation-realtime.test.mjs
```

Expected: FAIL because diagnostic events do not retain tool name or duration.

- [ ] **Step 3: Extend the diagnostic event shape**

Store only `name`, integer `durationMs`, and `code` for tool events. Render the secondary label as `get_weather · 420 ms` or `get_weather · timeout`. Do not render arguments or result content.

- [ ] **Step 4: Run browser module tests**

```bash
node --test scripts/public-conversation-realtime.test.mjs \
  scripts/public-conversation-turn-model.test.mjs \
  scripts/public-conversation-audio.test.mjs
node --check docs/api/public-conversation-demo.js
git diff --check
```

Expected: all tests and checks pass.

- [ ] **Step 5: Commit**

```bash
git add docs/api/public-conversation-realtime.js docs/api/public-conversation-demo.js \
  docs/api/public-conversation-demo.css scripts/public-conversation-realtime.test.mjs
git commit -m "feat: show public tool lifecycle events"
```

### Task 6: Bind weather and news to the current Agent safely

**Files:**
- Create: `scripts/bind-public-readonly-tools.mjs`
- Modify: `docs/public-conversation-progress.md`
- Modify: `docs/project-chain-roadmap.md`

- [ ] **Step 1: Create a reusable binding script**

The script must require `PUBLIC_API_BASE`, `PUBLIC_AUTHORIZATION`, and `PUBLIC_AGENT_ID`. It must GET the current profile, preserve all existing editable fields and model bindings, merge these Skill bindings, PUT the profile, and publish a new Agent snapshot:

```javascript
const readonlySkills = [
  { skillId: "skill-weather", versionMode: "LATEST", fixedVersion: null,
    overrideJson: null, triggerPriority: 100, enabled: true },
  { skillId: "skill-news", versionMode: "LATEST", fixedVersion: null,
    overrideJson: null, triggerPriority: 90, enabled: true },
];
```

Before mutation, print only the Agent ID, old active version, and existing Skill IDs. After mutation, fetch the profile again and print the new active version and bound Skill IDs. Never print the authorization value, provider configuration, prompts, or secrets.

- [ ] **Step 2: Verify the script syntax**

```bash
node --check scripts/bind-public-readonly-tools.mjs
```

Expected: exit code `0`.

- [ ] **Step 3: Rebuild and restart local Java and Python services**

Use the repository's current Docker development commands. Confirm `8002` returns public config and `8003` accepts HTTP requests before continuing.

- [ ] **Step 4: Run the binding script against the current local Agent**

```bash
PUBLIC_API_BASE=http://127.0.0.1:8002/xiaozhi \
PUBLIC_AUTHORIZATION="Bearer $LOCAL_TEST_TOKEN" \
PUBLIC_AGENT_ID=9e4a23e90532490e9faf94a111c0256d \
node scripts/bind-public-readonly-tools.mjs
```

Expected: the Agent receives `skill-weather` and `skill-news`, and a new active version is published.

- [ ] **Step 5: Update progress documentation**

Record that public sessions now support only the two bound read-only server plugins, list the tool events, and keep device/MCP tools in the unfinished section.

- [ ] **Step 6: Commit**

```bash
git add scripts/bind-public-readonly-tools.mjs docs/public-conversation-progress.md docs/project-chain-roadmap.md
git commit -m "docs: record public readonly tool rollout"
```

### Task 7: Cross-layer and real-provider acceptance

**Files:**
- Modify only when verification reveals a defect: files from Tasks 1-6

- [ ] **Step 1: Run Java and Python gates**

```bash
cd server/main/manager-api
mvn -q -Dtest='xiaozhi.modules.conversation.**,CapabilityBootstrapServiceTest' test

cd ../xiaozhi-server
../../../.venv312/bin/python -m pytest -q \
  tests/test_public_conversation_*.py \
  tests/test_unified_tool_initialization.py \
  tests/test_device_wake_word_config.py
```

Expected: all selected tests pass.

- [ ] **Step 2: Verify an unbound Agent cannot call tools**

Create or use a temporary published Agent without Skill bindings. Ask for Shenzhen weather and assert no `tool.started`, `tool.completed`, or `tool.failed` event appears. Delete the temporary Agent through the management API after the check.

- [ ] **Step 3: Verify real weather grounding**

Create a fresh public conversation for the bound purple companion Agent and send `深圳今天天气怎么样`.

Expected event order includes:

```text
session.ready -> turn.started -> tool.started(get_weather)
-> tool.completed(get_weather) -> llm.delta -> tts.audio -> turn.completed
```

The reply must mention the queried location and values present in the tool result. It must not claim that it checked a phone app.

- [ ] **Step 4: Verify real news grounding**

Send `今天有什么新闻` in a fresh turn.

Expected event order includes `tool.started(get_news_from_newsnow)` and either `tool.completed` followed by grounded text/TTS, or `tool.failed` followed by an explicit unavailable message. A reply without any tool event is a failure.

- [ ] **Step 5: Verify failure behavior**

Run the public tool runtime test with an injected timeout and confirm `tool.failed` uses code `timeout`, no raw exception or credential appears, the turn returns a readable unavailable reply, and the next text turn succeeds on the same socket.

- [ ] **Step 6: Verify the realtime browser tab**

Open the demo, connect the Agent, enter realtime call mode, ask both questions, and confirm tool event rows show tool names and durations while the content area shows user text, AI grounded text, and AI audio. Browser console must contain zero errors.

- [ ] **Step 7: Run final repository checks**

```bash
node --test scripts/public-conversation-audio.test.mjs \
  scripts/public-conversation-turn-model.test.mjs \
  scripts/public-conversation-realtime.test.mjs
node --check docs/api/public-conversation-demo.js
node --check scripts/bind-public-readonly-tools.mjs
git diff --check
```

Expected: all tests and checks pass.
