# Stage One Runtime Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture a reproducible, real-device baseline for the current microphone-to-speaker conversation chain without changing MQTT, WebSocket, firmware, provider, or database behavior.

**Architecture:** Treat the ESP32 device, MQTT gateway, Python conversation runtime, Java manager API, and companion console as separate evidence sources connected by `device_id`, `client_id`, `session_id`, and `sentence_id`. First collect configuration and runtime facts, then execute one controlled conversation, then record failure-path observations and convert the result into a checked acceptance record.

**Tech Stack:** ESP-IDF serial monitor, MQTT gateway Node.js process, Python `xiaozhi-server`, Java Spring Boot `manager-api`, existing Python/MQTT/Java/console tests, Markdown evidence records.

---

## Files and ownership

The baseline report is the only new artifact produced by this plan. It belongs under `docs/` and records measured facts, not conclusions inferred from code. The existing protocol notes remain source references. No production source file is modified unless a baseline gap proves that a missing event prevents correlation; such a change requires a separate approved plan.

### Task 1: Freeze the workspace and identify the device build

**Files:**
- Read: `AGENTS.md`
- Read: `firmware/main/boards/*/config.json`
- Read: `build/*/sdkconfig`
- Read: `firmware/main/protocols/websocket_protocol.cc`
- Create: `docs/chain-baseline-2026-08-23.md`

- [ ] **Step 1: Record the starting commit and unrelated worktree state**

Run:

```bash
git rev-parse HEAD
git status --short
```

Expected: the report records commit `65e5fdf` as the plan baseline and lists existing untracked paths without staging or deleting them.

- [ ] **Step 2: Match the connected serial port to a firmware target without flashing**

Run:

```bash
ls -l /dev/tty.usbmodem2101
find build -path '*/sdkconfig' -type f -print | sort
rg -n "CONFIG_BOARD_TYPE|CONFIG_BOARD_NAME|CONFIG_IDF_TARGET|CONFIG_USE_CUSTOM_WAKE_WORD" build firmware/main/boards --glob 'sdkconfig' --glob 'config.json'
```

Expected: the report names the candidate board directory, target chip, active build directory, and whether generated `sdkconfig` agrees with the board `config.json`. Do not run `flash`, `erase`, `write_flash`, `merge-bin`, or any command that writes NVS or application partitions.

- [ ] **Step 3: Record the device identity using a read-only serial interaction**

Use the firmware monitor from the identified build directory:

```bash
idf.py -p /dev/tty.usbmodem2101 monitor
```

Capture the first startup block after a normal device restart and stop the monitor with `Ctrl+]`. Record the board type, firmware/application version, MAC or device ID, protocol mode, sample rate, audio format, wake-word mode, and feature flags. If the monitor cannot resolve the project, record the exact error and use the existing device debug log path instead; do not rebuild or flash as part of this task.

- [ ] **Step 4: Fill the report's hardware and build section**

Write the measured values into `docs/chain-baseline-2026-08-23.md` under `Hardware and Build`. Include the serial path, timestamp in Asia/Shanghai, source commit, board config path, build path, and the explicit statement that NVS and flash were not modified.

- [ ] **Step 5: Commit the evidence skeleton**

Run:

```bash
git add docs/chain-baseline-2026-08-23.md
git commit -m "docs: start runtime chain baseline"
```

Expected: one commit contains only the new report skeleton and no existing untracked artifact.

### Task 2: Map the live configuration source chain

**Files:**
- Read: `server/main/xiaozhi-server/config.yaml`
- Read: `server/main/xiaozhi-server/config_from_api.yaml`
- Read: `server/main/xiaozhi-server/config/config_loader.py`
- Read: `server/main/xiaozhi-server/config/manage_api_client.py`
- Read: `server/main/xiaozhi-server/core/connection.py`
- Read: `server/main/manager-api/src/main/java/xiaozhi/modules/agent/controller/AgentSnapshotController.java`
- Read: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/controller/InternalCapabilityController.java`
- Modify: `docs/chain-baseline-2026-08-23.md`

- [ ] **Step 1: Identify the running Python and Java configuration endpoints**

Run:

```bash
rg -n "read_config_from_api|config_from_api|manage_api|capability|snapshot|active|agentId|deviceId" server/main/xiaozhi-server/config server/main/xiaozhi-server/core/connection.py server/main/manager-api/src/main/java/xiaozhi/modules/agent server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability --glob '*.py' --glob '*.java'
```

Expected: the report names the exact manager-api endpoint(s), Python client method(s), cache layer, and connection-time snapshot assignment used by the live runtime.

- [ ] **Step 2: Read the effective provider selection**

Run:

```bash
rg -n "selected_module|ASR:|LLM:|TTS:|VAD:|Memory:|voice|ttsVoice|system_prompt|personality|companion_identity" server/main/xiaozhi-server/config.yaml server/main/xiaozhi-server/config_from_api.yaml server/main/xiaozhi-server/data/.config.yaml server/main/xiaozhi-server/core --glob '*.py' --glob '*.yaml'
```

Expected: the report distinguishes local defaults from values loaded from manager-api and records the effective ASR, VAD, LLM, TTS, Memory, voice, prompt, and Agent version sources. Secret values must be replaced with `[redacted]` in the report.

- [ ] **Step 3: Confirm configuration precedence on a live connection**

Use the existing Python debug events and manager-api read-only endpoints. Capture the connection's `device_id`, `client_id`, `session_id`, Agent ID, active version, selected providers, and effective voice. Do not edit a profile, publish a version, or change global model settings during this task.

- [ ] **Step 4: Update the report with the configuration graph**

Add a concise `Configuration Graph` section that states, for each runtime field, its persistence source, publication step, Python loading method, connection snapshot field, and provider consumer. Mark any field whose source cannot be proven as `unverified` and include the file or endpoint that must be inspected next.

### Task 3: Capture one real end-to-end conversation

**Files:**
- Read: `mqtt-gateway/app.js`
- Read: `mqtt-gateway/config/mqtt.json.example`
- Read: `server/main/xiaozhi-server/core/handle/receiveAudioHandle.py`
- Read: `server/main/xiaozhi-server/core/connection.py`
- Read: `server/main/xiaozhi-server/core/handle/sendAudioHandle.py`
- Read: `server/main/xiaozhi-server/core/debug_events.py`
- Modify: `docs/chain-baseline-2026-08-23.md`

- [ ] **Step 1: Verify service health before speaking to the device**

Run the read-only checks:

```bash
curl -fsS http://127.0.0.1:8420/health
curl -fsS http://127.0.0.1:8000/health 2>/dev/null || true
lsof -nP -iTCP:1883 -sTCP:LISTEN
```

Expected: record the actual health responses and the process owning the MQTT listener. A failed check is recorded as a baseline failure; do not restart services as part of this step.

- [ ] **Step 2: Start timestamped log capture without changing service configuration**

Capture the existing process outputs using the terminal or service log files already configured by the deployment. Keep one file per layer under an ignored temporary directory, then record only event names and timestamps in the report. Never copy credentials, raw audio, or full user prompts into the committed report.

- [ ] **Step 3: Execute one controlled voice turn**

Use the device's normal wake-word or button flow. Speak a fixed sentence that does not invoke a device tool, then wait for the complete spoken response. Record the wall-clock start and end time. The expected event sequence is device audio uplink, gateway bridge, Python audio receive, VAD start and stop, ASR final text, `llm.started`, first visible LLM text, TTS first audio, device playback, and turn completion.

- [ ] **Step 4: Correlate the turn across layers**

Use `device_id`, `client_id`, `session_id`, `sentence_id`, MQTT session ID, and any gateway sequence fields to join the logs. If one identifier is absent at a boundary, record the exact boundary and do not invent a correlation. Record first-byte and completion latency for ASR, LLM, TTS, and total device response.

- [ ] **Step 5: Add the measured timeline to the report**

Write a table with event name, layer, timestamp, correlation ID, observed payload class, latency from the previous event, and result. Payloads must be summarized, not copied. Add a `Configuration at Turn Start` block containing the effective Agent version, prompt identity, model IDs, voice ID, and memory namespace.

### Task 4: Exercise the existing failure paths without changing code

**Files:**
- Read: `server/main/xiaozhi-server/tests/test_audio_barge_in.py`
- Read: `server/main/xiaozhi-server/tests/test_audio_latency_events.py`
- Read: `server/main/xiaozhi-server/tests/test_connection_tool_routing.py`
- Read: `mqtt-gateway/tests/test_start_local.py`
- Modify: `docs/chain-baseline-2026-08-23.md`

- [ ] **Step 1: Run the focused existing tests**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest -q tests/test_audio_barge_in.py tests/test_audio_latency_events.py tests/test_connection_tool_routing.py
cd ../../../mqtt-gateway
python -m pytest -q tests/test_start_local.py tests/test_start_local_config_sync.py tests/test_hello_feature_forwarding.py
```

Expected: record pass/fail counts and warnings. A failure is evidence for the baseline, not permission to modify the test or implementation in this plan.

- [ ] **Step 2: Observe device insertion and cancellation**

During a second normal turn, speak while the device is playing the previous response. Record whether `abort`, TTS queue cleanup, and a new `sentence_id` appear in the logs, and whether audio from the first turn continues after the second turn starts.

- [ ] **Step 3: Observe reconnect behavior**

Without changing firmware or credentials, disconnect the device network for a bounded interval, restore it, and record MQTT reconnect, WebSocket bridge re-establishment, new session identity, and whether the old session emits any audio after reconnect. Do not perform a flash reset or erase.

- [ ] **Step 4: Record provider and idle timeout observations**

Use existing configuration and test fixtures to document provider timeout handling and no-voice close behavior. Do not intentionally invalidate production credentials. If a failure path cannot be safely exercised on the live device, mark it `test-only` and cite the exact test file.

- [ ] **Step 5: Complete the failure-path section**

For each path, record trigger, expected behavior from code or tests, observed behavior, evidence source, and whether the result is `pass`, `fail`, or `not exercised`.

### Task 5: Validate the baseline and hand off the next slice

**Files:**
- Modify: `docs/chain-baseline-2026-08-23.md`
- Read: `docs/superpowers/specs/2026-08-23-ai-companion-chain-and-platform-plan-design.md`

- [ ] **Step 1: Check required evidence coverage**

The report must contain hardware/build identity, service health, configuration graph, one complete real-device timeline, latency observations, failure-path observations, secrets-redaction confirmation, and a list of unresolved gaps with exact source paths.

- [ ] **Step 2: Run document and repository checks**

Run:

```bash
git diff --check
rg -n "sk-[A-Za-z0-9]{12,}|Bearer [A-Za-z0-9._-]{12,}|(api[_-]?key|secret|password)[[:space:]]*[:=]" docs/chain-baseline-2026-08-23.md
```

Expected: `git diff --check` produces no output. The secret scan produces no unredacted credential-like value; false positives must be removed or explained outside the committed report.

- [ ] **Step 3: Review against the design spec**

Confirm the report does not propose MQTT changes, external API implementation, code deletion, firmware flashing, NVS modification, or provider replacement. Those belong to later stages and require separate plans.

- [ ] **Step 4: Commit the completed baseline**

Run:

```bash
git add docs/chain-baseline-2026-08-23.md
git commit -m "docs: record real device conversation baseline"
```

Expected: the commit contains only the baseline report. Temporary logs remain outside Git.

## Self-review against the design spec

The plan covers the first execution task in the design spec: hardware and application identity, configuration source proof, one end-to-end device conversation, failure-path observations, correlation IDs, latency, provider selection, and an explicit stop before code cleanup or protocol changes. It deliberately does not implement the later external API, role-effectiveness matrix, or legacy cleanup because each is a separate sub-project with its own contract and rollback surface.

Every step names a path or command, states what evidence is expected, and forbids destructive device operations. The identifiers used in later tasks (`device_id`, `client_id`, `session_id`, `sentence_id`, Agent version, and voice ID) match the current Python, MQTT, and manager-api vocabulary.
