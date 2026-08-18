# Camera Capability Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Restore camera tool visibility for \`zhengchen-cam\` devices while reporting real camera initialization state.

**Architecture:** Keep the existing official Xiaozhi camera transport and image upload path. Add a runtime readiness contract and board capability JSON on the firmware side. In the server, extend the no-matched-Skill path with an exact allowlist for device-local tools selected by camera and other device-control keywords; server plugins and remote MCP tools remain hidden.

**Tech Stack:** ESP-IDF C++, ESP32 camera component, Python \`pytest\`, existing \`ConnectionHandler\` and firmware source-contract tests.

---

### Task 1: Add the failing server routing regression tests

**Files:**
- Modify: \`server/main/xiaozhi-server/tests/test_connection_tool_routing.py\`
- Test: \`server/main/xiaozhi-server/tests/test_connection_tool_routing.py\`

- [ ] **Step 1: Extend the fake connection with the API-mode no-Skill state**

Add a \`SkillTurn\` test helper and let \`FakeConnection\` opt into \`read_config_from_api\`:

\`\`\`python
class FakeSkillTurn:
    def __init__(self, skill=None, allowed_tool_names=None):
        self.skill = skill
        self.allowed_tool_names = frozenset(allowed_tool_names or {"handle_exit_intent"})


class FakeConnection:
    def __init__(self, config=None, tools_enabled=True, read_config_from_api=False):
        self.config = config or {}
        self.func_handler = FakeHandler()
        self.llm = type("LLM", (), {"tools_enabled": tools_enabled})()
        self.read_config_from_api = read_config_from_api
        self._skill_turn = FakeSkillTurn()
\`\`\`

- [ ] **Step 2: Add one test for camera keyword routing in API mode**

\`\`\`python
def test_api_mode_camera_query_exposes_only_reported_camera_tool():
    select_tools = load_method("_select_functions_for_query")
    selected = select_tools(
        FakeConnection(read_config_from_api=True),
        "请打开摄像头看看桌面",
    )

    assert names(selected) == [
        "handle_exit_intent",
        "self_camera_take_photo",
    ]
\`\`\`

- [ ] **Step 3: Add tests for no keyword and unreported camera tool**

\`\`\`python
def test_api_mode_normal_chat_keeps_only_safe_tool():
    select_tools = load_method("_select_functions_for_query")
    selected = select_tools(FakeConnection(read_config_from_api=True), "陪我聊聊天")

    assert names(selected) == ["handle_exit_intent"]


def test_api_mode_camera_query_does_not_create_missing_device_tool():
    class CameraMissingHandler(FakeHandler):
        def get_functions(self):
            return [
                tool for tool in super().get_functions()
                if tool["function"]["name"] != "self_camera_take_photo"
            ]

    connection = FakeConnection(read_config_from_api=True)
    connection.func_handler = CameraMissingHandler()
    select_tools = load_method("_select_functions_for_query")

    selected = select_tools(connection, "请拍照看看")

    assert names(selected) == ["handle_exit_intent"]
\`\`\`

- [ ] **Step 4: Run the focused tests and confirm the expected failure**

Run: \`pytest -q server/main/xiaozhi-server/tests/test_connection_tool_routing.py\`

Expected: FAIL because \`_select_functions_for_query\` currently keeps only \`handle_exit_intent\` whenever a no-Skill \`SkillTurn\` is active.

### Task 2: Implement the server-side device-tool allowlist

**Files:**
- Modify: \`server/main/xiaozhi-server/core/connection.py:1375-1415\`
- Test: \`server/main/xiaozhi-server/tests/test_connection_tool_routing.py\`

- [ ] **Step 1: Define exact keyword-to-device-tool mappings inside the function-selection branch**

Use sanitized MCP names already exposed by \`MCPClient\`:

\`\`\`python
device_tool_keywords = (
    (("拍照", "照片", "相机", "摄像头", "画面", "看看"), ("self_camera_take_photo",)),
    (("音量", "声音"), ("self_get_device_status", "self_audio_speaker_set_volume")),
    (("亮度",), ("self_get_device_status", "self_screen_set_brightness")),
    (("屏幕", "主题"), ("self_get_device_status", "self_screen_set_theme")),
    (("设备状态", "电量"), ("self_get_device_status",)),
)
\`\`\`

- [ ] **Step 2: Use the mapping only for an unmatched SkillTurn in API mode**

Replace the existing early return inside \`_select_functions_for_query\` with this branch:

\`\`\`python
        skill_turn = getattr(self, "_skill_turn", None)
        if skill_turn is not None:
            functions = list(self.func_handler.get_functions())
            if (
                getattr(self, "read_config_from_api", False)
                and getattr(skill_turn, "skill", None) is None
            ):
                text = query or ""
                allowed = {"handle_exit_intent"}
                for keywords, tool_names in device_tool_keywords:
                    if any(keyword in text for keyword in keywords):
                        allowed.update(tool_names)
                return [
                    function
                    for function in functions
                    if function.get("function", {}).get("name") in allowed
                ]
            return list(
                self.func_handler.get_functions(skill_turn.allowed_tool_names)
            )
\`\`\`

Keep the existing non-API keyword behavior unchanged after this branch. The new branch must only filter the functions already reported by the device MCP client.

- [ ] **Step 3: Run the focused tests and the existing routing tests**

Run: \`pytest -q server/main/xiaozhi-server/tests/test_connection_tool_routing.py\`

Expected: PASS with the camera query returning \`handle_exit_intent\` and \`self_camera_take_photo\`, normal chat returning only \`handle_exit_intent\`, and missing camera tools staying absent.

### Task 3: Add failing firmware capability-contract tests

**Files:**
- Create: \`firmware/tests/test_zhengchen_camera_capability.py\`
- Test: \`firmware/tests/test_zhengchen_camera_capability.py\`

- [ ] **Step 1: Write source-contract tests for the camera readiness API and board JSON**

\`\`\`python
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ZhengchenCameraCapabilityTest(unittest.TestCase):
    def test_camera_interface_exposes_readiness_and_esp32_camera_implements_it(self):
        interface = (ROOT / "main/boards/common/camera.h").read_text(encoding="utf-8")
        implementation = (ROOT / "main/boards/common/esp32_camera.h").read_text(encoding="utf-8")

        self.assertIn("virtual bool IsReady() const", interface)
        self.assertIn("bool IsReady() const override", implementation)

    def test_zhengchen_board_reports_runtime_camera_capability(self):
        source = (ROOT / "main/boards/zhengchen-cam/zhengchen_cam_board.cc").read_text(encoding="utf-8")

        self.assertIn("virtual std::string GetBoardJson() override", source)
        self.assertIn('"has_camera"', source)
        self.assertIn("camera_->IsReady()", source)

    def test_zhengchen_double_click_handles_unavailable_camera(self):
        source = (ROOT / "main/boards/zhengchen-cam/zhengchen_cam_board.cc").read_text(encoding="utf-8")

        self.assertIn("if (camera == nullptr || !camera->Capture())", source)


if __name__ == "__main__":
    unittest.main()
\`\`\`

- [ ] **Step 2: Run the new firmware tests and confirm the expected failure**

Run: \`pytest -q firmware/tests/test_zhengchen_camera_capability.py\`

Expected: FAIL because the readiness method and \`zhengchen-cam\` capability JSON do not exist yet.

### Task 4: Implement firmware camera readiness and board reporting

**Files:**
- Modify: \`firmware/main/boards/common/camera.h\`
- Modify: \`firmware/main/boards/common/esp32_camera.h\`
- Modify: \`firmware/main/boards/zhengchen-cam/zhengchen_cam_board.cc\`
- Test: \`firmware/tests/test_zhengchen_camera_capability.py\`

- [ ] **Step 1: Add the readiness contract and implementation**

Add a default false method to \`Camera\` so non-camera implementations remain source-compatible:

\`\`\`cpp
virtual bool IsReady() const { return false; }
\`\`\`

Declare and implement the ESP32 camera method:

\`\`\`cpp
bool IsReady() const override { return streaming_on_; }
\`\`\`

The existing constructor already sets \`streaming_on_\` only after \`esp_camera_init\` succeeds, so no second initialization path is needed.

- [ ] **Step 2: Make the Zhengchen board expose only a ready camera and report capabilities**

Initialize the member to null, guard the double-click capture, and add board JSON fields:

\`\`\`cpp
Esp32Camera* camera_ = nullptr;

if (camera == nullptr || !camera->Capture()) {
    ESP_LOGE(TAG, "Camera capture failed");
}

virtual std::string GetBoardJson() override {
    auto json = WifiBoard::GetBoardJson();
    json.pop_back();
    json += R"(,"has_display":)" + std::string(display_ != nullptr ? "true" : "false");
    json += R"(,"has_camera":)" + std::string(GetCamera() != nullptr ? "true" : "false") + "}";
    return json;
}

virtual Camera* GetCamera() override {
    return camera_ != nullptr && camera_->IsReady() ? camera_ : nullptr;
}
\`\`\`

- [ ] **Step 3: Run firmware tests and inspect the diff**

Run: \`pytest -q firmware/tests/test_zhengchen_camera_capability.py firmware/tests/test_bread_s3cam_headless.py\`

Expected: PASS, with no changes outside the camera readiness contract and Zhengchen board capability reporting.

### Task 5: Run full verification

**Files:**
- Test: \`server/main/xiaozhi-server/tests/test_connection_tool_routing.py\`
- Test: \`firmware/tests/test_zhengchen_camera_capability.py\`

- [ ] **Step 1: Run the focused server suite**

Run: \`pytest -q server/main/xiaozhi-server/tests/test_connection_tool_routing.py server/main/xiaozhi-server/tests/test_device_skill_end_to_end.py\`

Expected: PASS with zero failures.

- [ ] **Step 2: Run the firmware source-contract suite**

Run: \`pytest -q firmware/tests\`

Expected: PASS with zero failures. If the repository environment cannot import a fixture dependency, report that exact blocker and still run the focused tests.

- [ ] **Step 3: Run the firmware compile check for the active target**

Run from \`firmware\`: \`idf.py build\`

Expected: exit code 0 for the existing ESP32-S3 \`zhengchen-cam\` configuration, or an environment-specific missing-IDF error recorded without changing generated build files.

- [ ] **Step 4: Review the final diff and status**

Run: \`git diff --check\` and \`git status --short\`

Expected: no whitespace errors, only the requested source, test, and plan/spec changes, and no modifications to the pre-existing \`.codex-tmp\` or \`.playwright-cli\` files.
