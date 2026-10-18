import ast
from pathlib import Path


CONNECTION_PATH = Path(__file__).parents[1] / "core/connection.py"


def load_connection_class_node():
    tree = ast.parse(CONNECTION_PATH.read_text(encoding="utf-8"))
    return next(
        node
        for node in tree.body
        if isinstance(node, ast.ClassDef) and node.name == "ConnectionHandler"
    )


def load_method(name):
    class_node = load_connection_class_node()
    method_node = next(
        node
        for node in class_node.body
        if isinstance(node, ast.FunctionDef) and node.name == name
    )
    module = ast.Module(body=[method_node], type_ignores=[])
    namespace = {}
    exec(compile(ast.fix_missing_locations(module), str(CONNECTION_PATH), "exec"), namespace)
    return namespace[name]


class FakeHandler:
    def get_functions(self, allowed_names=None):
        functions = [
            {"type": "function", "function": {"name": "handle_exit_intent"}},
            {"type": "function", "function": {"name": "get_lunar"}},
            {"type": "function", "function": {"name": "self_get_device_status"}},
            {"type": "function", "function": {"name": "self_audio_speaker_set_volume"}},
            {"type": "function", "function": {"name": "self_screen_set_brightness"}},
            {"type": "function", "function": {"name": "self_camera_take_photo"}},
        ]
        if allowed_names is None:
            return functions
        return [
            function
            for function in functions
            if function["function"]["name"] in allowed_names
        ]


class FakeSkillTurn:
    def __init__(self, skill=None, allowed_tool_names=None):
        self.skill = skill
        self.allowed_tool_names = frozenset(
            allowed_tool_names or {"handle_exit_intent"}
        )


class FakeConnection:
    def __init__(self, config=None, tools_enabled=True, read_config_from_api=False):
        self.config = config or {}
        self.func_handler = FakeHandler()
        self.llm = type("LLM", (), {"tools_enabled": tools_enabled})()
        self.read_config_from_api = read_config_from_api
        self._skill_turn = FakeSkillTurn() if read_config_from_api else None


def names(tools):
    return [tool["function"]["name"] for tool in tools]


def test_normal_chat_keeps_only_safe_chat_tools():
    select_tools = load_method("_select_functions_for_query")
    selected = select_tools(FakeConnection(), "你好，今天心情怎么样")

    assert names(selected) == ["handle_exit_intent"]


def test_device_control_query_receives_device_tools():
    select_tools = load_method("_select_functions_for_query")
    selected = select_tools(FakeConnection(), "把音量调到百分之五十")

    assert "self_audio_speaker_set_volume" in names(selected)
    assert "self_get_device_status" in names(selected)


def test_tools_for_chat_configuration_restores_full_tool_list():
    select_tools = load_method("_select_functions_for_query")
    selected = select_tools(FakeConnection({"tools_for_chat": True}), "你好")

    assert names(selected) == names(FakeHandler().get_functions())


def test_llm_tools_switch_disables_all_tools():
    select_tools = load_method("_select_functions_for_query")
    selected = select_tools(FakeConnection(tools_enabled=False), "把音量调小")

    assert selected == []


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


def test_api_mode_camera_query_authorizes_the_exposed_device_tool():
    select_tools = load_method("_select_functions_for_query")
    connection = FakeConnection(read_config_from_api=True)

    select_tools(connection, "请打开摄像头看看桌面")

    assert "self_camera_take_photo" in connection._skill_turn.allowed_tool_names


def test_api_mode_normal_chat_keeps_only_safe_tool():
    select_tools = load_method("_select_functions_for_query")
    selected = select_tools(FakeConnection(read_config_from_api=True), "陪我聊聊天")

    assert names(selected) == ["handle_exit_intent"]


def test_api_mode_camera_query_does_not_create_missing_device_tool():
    class CameraMissingHandler(FakeHandler):
        def get_functions(self, allowed_names=None):
            return [
                tool
                for tool in super().get_functions(allowed_names)
                if tool["function"]["name"] != "self_camera_take_photo"
            ]

    connection = FakeConnection(read_config_from_api=True)
    connection.func_handler = CameraMissingHandler()
    select_tools = load_method("_select_functions_for_query")

    selected = select_tools(connection, "请拍照看看")

    assert names(selected) == ["handle_exit_intent"]
