# TencentDB Memory Recall Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore TencentDB memory recall on Python 3.10, prevent long conversations from leaving unprocessed tail messages, and recover the persisted user-name fact into L1.

**Architecture:** Keep the existing provider and MemoryCore contracts. Replace the Python 3.11-only timeout context with `asyncio.wait_for`, then split captures longer than ten messages into stable MemoryCore session fragments so each fragment receives its own extraction task. Recover the already persisted name through a one-time isolated replay after deploying the code.

**Tech Stack:** Python 3.10, asyncio, pytest, pytest-asyncio, httpx, TencentDB MemoryCore v3, Docker Compose.

---

### Task 1: Make layered recall compatible with Python 3.10

**Files:**

- Modify: `server/main/xiaozhi-server/tests/test_tencentdb_memory_provider_recall.py`
- Modify: `server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py:405`

- [ ] **Step 1: Write the failing Python 3.10 compatibility test**

Add this test beside the existing recall tests:

```python
@pytest.mark.asyncio
async def test_recall_does_not_require_asyncio_timeout(monkeypatch):
    provider = make_provider()
    configure_success(provider)
    monkeypatch.delattr(asyncio, "timeout", raising=False)

    result = await provider.query_memory("我是谁")

    assert "用户喜欢草莓" in result
    provider.client.atomic_search.assert_awaited_once()
    provider.client.scenario_list.assert_awaited_once()
    provider.client.core_read.assert_awaited_once()
    assert provider.get_diagnostics()["degraded_reason"] is None
```

- [ ] **Step 2: Run the test and verify the current implementation fails**

Run:

```bash
cd server/main/xiaozhi-server
pytest -q tests/test_tencentdb_memory_provider_recall.py::test_recall_does_not_require_asyncio_timeout
```

Expected: FAIL because all three layers report `AttributeError` and the result is empty.

- [ ] **Step 3: Replace the Python 3.11-only timeout context**

Change the helper to:

```python
@staticmethod
async def _with_layer_timeout(awaitable):
    return await asyncio.wait_for(awaitable, timeout=LAYER_TIMEOUT_SECONDS)
```

- [ ] **Step 4: Run the compatibility and degradation tests**

Run:

```bash
cd server/main/xiaozhi-server
pytest -q tests/test_tencentdb_memory_provider_recall.py
```

Expected: all recall tests PASS without unawaited-coroutine warnings.

- [ ] **Step 5: Commit the recall fix**

```bash
git add server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py server/main/xiaozhi-server/tests/test_tencentdb_memory_provider_recall.py
git commit -m "fix: support TencentDB recall on Python 3.10"
```

### Task 2: Split long captures into independently extractable sessions

**Files:**

- Modify: `server/main/xiaozhi-server/tests/test_tencentdb_memory_provider_capture.py`
- Modify: `server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py:20-90`

- [ ] **Step 1: Write the failing long-capture test**

Add a test that submits twenty-three messages and checks three ordered calls:

```python
@pytest.mark.asyncio
async def test_save_splits_long_conversation_into_extractable_session_fragments():
    provider = make_provider()
    provider.client.conversation_add.return_value = {"accepted_ids": ["m"]}
    messages = [message("user", f"消息 {index}") for index in range(23)]

    assert await provider.save_memory(messages, "session-a") is True

    calls = provider.client.conversation_add.await_args_list
    assert [item.args[1] for item in calls] == [
        "session-a",
        "session-a:memory-part:2",
        "session-a:memory-part:3",
    ]
    assert [len(item.args[2]) for item in calls] == [10, 10, 3]
    assert [entry["content"] for item in calls for entry in item.args[2]] == [
        f"消息 {index}" for index in range(23)
    ]
    assert all(item.kwargs["task_id"] == "device-a" for item in calls)
```

- [ ] **Step 2: Write the failing fragment-retry test**

Add a test proving uncertain writes query the current fragment rather than the original session:

```python
@pytest.mark.asyncio
async def test_long_capture_confirms_uncertain_write_against_current_fragment():
    provider = make_provider()
    provider.client.conversation_add.side_effect = [
        {"accepted_ids": ["first"]},
        TencentDbMemoryError("timeout", retryable=True),
    ]
    provider.client.conversation_query.return_value = {
        "messages": [
            {"role": "user", "content": f"消息 {index}", "timestamp": datetime.now(timezone.utc).isoformat()}
            for index in reversed(range(10, 12))
        ],
        "total": 2,
    }

    messages = [message("user", f"消息 {index}") for index in range(12)]
    assert await provider.save_memory(messages, "session-a") is True

    query = provider.client.conversation_query.await_args
    assert query.kwargs["session_id"] == "session-a:memory-part:2"
    assert query.kwargs["limit"] == 2
```

- [ ] **Step 3: Run both new tests and verify they fail**

Run:

```bash
cd server/main/xiaozhi-server
pytest -q \
  tests/test_tencentdb_memory_provider_capture.py::test_save_splits_long_conversation_into_extractable_session_fragments \
  tests/test_tencentdb_memory_provider_capture.py::test_long_capture_confirms_uncertain_write_against_current_fragment
```

Expected: FAIL because the current provider sends one twenty-three-message request.

- [ ] **Step 4: Add capture batching and a per-fragment write helper**

Add the constant:

```python
CAPTURE_BATCH_SIZE = 10
```

Replace the single-write body with:

```python
for index, batch in enumerate(self._chunks(outgoing, CAPTURE_BATCH_SIZE)):
    fragment_id = self._capture_session_id(session_id, index)
    if not await self._save_capture_fragment(fragment_id, batch):
        return False
return True
```

Add these helpers:

```python
@staticmethod
def _capture_session_id(session_id: str, index: int) -> str:
    return session_id if index == 0 else f"{session_id}:memory-part:{index + 1}"

async def _save_capture_fragment(self, session_id: str, outgoing: list[dict]) -> bool:
    try:
        result = await self._add_conversation(session_id, outgoing)
        self._remember_request_id(result)
        return True
    except Exception as exception:
        if not self._is_uncertain_write(exception):
            self._log_capture_failure(exception)
            return False

    if await self._tail_matches(session_id, outgoing):
        return True

    try:
        result = await self._add_conversation(session_id, outgoing)
        self._remember_request_id(result)
        return True
    except Exception as exception:
        self._log_capture_failure(exception)
        return False
```

- [ ] **Step 5: Run all TencentDB provider tests**

Run:

```bash
cd server/main/xiaozhi-server
pytest -q tests/test_tencentdb_memory_provider_capture.py tests/test_tencentdb_memory_provider_recall.py tests/test_tencentdb_memory_provider_management.py
```

Expected: all tests PASS.

- [ ] **Step 6: Commit capture batching**

```bash
git add server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py server/main/xiaozhi-server/tests/test_tencentdb_memory_provider_capture.py
git commit -m "fix: drain long TencentDB memory captures"
```

### Task 3: Deploy and recover the persisted user name

**Files:**

- Runtime change only: `ai-plush-companion-xiaozhi-server-dev`
- Persistent data: `xiaozhi-server_tencentdb_memory_data`

- [ ] **Step 1: Run the full Python test suite before deployment**

Run:

```bash
cd server/main/xiaozhi-server
pytest -q
```

Expected: zero failures and zero unawaited-coroutine warnings.

- [ ] **Step 2: Restart xiaozhi-server and verify direct recall**

Restart the container, instantiate `MemoryProvider` inside the Python 3.10 container with the real user, profile and device metadata, then call `query_memory("你是谁？")`.

Expected: the returned string contains the existing L1 instruction that the AI is called “紫萱”, diagnostics show an L1 hit, and `degraded_reason` is null.

- [ ] **Step 3: Replay the persisted name through an isolated recovery session**

Generate `timestamp="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"`, then POST a two-message conversation to MemoryCore using the existing user, profile and device isolation:

```json
{
  "team_id": "ai-plush-companion:user:2086833405813424129",
  "user_id": "2086833405813424129",
  "agent_id": "9e4a23e90532490e9faf94a111c0256d",
  "session_id": "recovery-user-name-20260816",
  "task_id": "9c:13:9e:8a:14:a4",
  "messages": [
    {"role": "user", "content": "我叫潇潇。", "timestamp": "$timestamp"},
    {"role": "assistant", "content": "潇潇，这个名字我记住了。", "timestamp": "$timestamp"}
  ]
}
```

Build the request body with shell `printf` so the generated timestamp is inserted as JSON data.

- [ ] **Step 4: Wait for and verify the recovered L1**

Poll `/v3/atomic/query` for at most 120 seconds. Expected: an item states that the user's name is “潇潇” and keeps `task_id` equal to the real device ID.

- [ ] **Step 5: Restart both services and verify persistence plus recall**

Restart MemoryCore and xiaozhi-server. Query `/v3/atomic/query` and call `MemoryProvider.query_memory("我的名字是什么？")` inside the Python 3.10 container.

Expected: the name item remains persisted, the recall result contains “潇潇”, and diagnostics contain no `AttributeError` degradation.

- [ ] **Step 6: Verify the management endpoint**

Call `GET /internal/companion-memory` for device `9c:13:9e:8a:14:a4` with the current server secret.

Expected: HTTP 200 and the item list includes the recovered user-name memory.

### Task 4: Final regression and repository check

**Files:**

- Verify only

- [ ] **Step 1: Run targeted tests again after container verification**

```bash
cd server/main/xiaozhi-server
pytest -q tests/test_tencentdb_memory_provider_capture.py tests/test_tencentdb_memory_provider_recall.py tests/test_tencentdb_memory_provider_management.py tests/integration/test_tencentdb_memory_provider_flow.py
```

Expected: zero failures and no runtime warnings.

- [ ] **Step 2: Check the final diff and preserve unrelated work**

```bash
git status --short --branch
git diff --check
git log -n 4 --oneline
```

Expected: the two fix commits and the design/plan commits are on `main`; unrelated pre-existing working-tree changes remain untouched.
