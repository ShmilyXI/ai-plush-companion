import ast
import asyncio
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional

import pytest

from core.capabilities.models import CapabilityBundle
from core.capabilities.runtime import SkillTurnRuntime
from core.capabilities.snapshots import build_device_tool_snapshot


ROOT = Path(__file__).parents[1]
CONNECTION_PATH = ROOT / "core/connection.py"
TOOL_MANAGER_PATH = ROOT / "core/providers/tools/unified_tool_manager.py"


def bundle():
    return CapabilityBundle.parse(
        {
            "deviceId": "device-a",
            "configVersion": 7,
            "skills": [
                {
                    "id": "skill-weather",
                    "version": 2,
                    "name": "天气查询",
                    "description": "查询天气",
                    "executionPrompt": "先确认地点，再查询真实天气。",
                    "semanticThreshold": 0.7,
                    "responseMode": "LLM",
                    "timeoutMs": 30000,
                    "failureMessage": "天气暂时查不到。",
                    "bindingPriority": 10,
                    "triggers": [
                        {
                            "type": "KEYWORD",
                            "value": "天气",
                            "priority": 100,
                            "caseSensitive": False,
                            "enabled": True,
                        }
                    ],
                    "toolNames": ["get_weather"],
                    "defaults": {"location": "杭州"},
                }
            ],
            "tools": {
                "get_weather": {
                    "name": "get_weather",
                    "type": "PLUGIN",
                    "refId": "plugin-weather",
                    "alias": None,
                    "purpose": "查询天气",
                    "required": True,
                    "defaults": {
                        "location": "杭州",
                        "api_host": "weather.example.com",
                        "api_key_secret_id": "secret-weather",
                    },
                }
            },
        }
    )


class RuleOnlyClassifier:
    async def classify(self, utterance, candidates):
        raise AssertionError("确定性命中不应调用分类器")


class SelectingClassifier:
    async def classify(self, utterance, candidates):
        return candidates[0]


@pytest.mark.asyncio
async def test_selected_skill_exposes_only_mapped_tool_and_system_tool():
    turn = await SkillTurnRuntime().select(bundle(), "杭州天气怎么样", RuleOnlyClassifier())

    assert turn.skill.id == "skill-weather"
    assert turn.trigger_mode == "RULE"
    assert turn.allowed_tool_names == frozenset({"handle_exit_intent", "get_weather"})


@pytest.mark.asyncio
async def test_direct_chat_exposes_only_required_system_tool():
    turn = await SkillTurnRuntime().select(bundle(), "陪我聊聊天", SelectingClassifier())
    no_match = await SkillTurnRuntime().select(bundle(), "陪我聊聊天", None)

    assert turn.skill.id == "skill-weather"
    assert no_match.skill is None
    assert no_match.allowed_tool_names == frozenset({"handle_exit_intent"})


@pytest.mark.asyncio
async def test_tools_switch_disables_every_tool():
    turn = await SkillTurnRuntime().select(
        bundle(), "杭州天气怎么样", RuleOnlyClassifier(), tools_enabled=False
    )

    assert turn.skill is None
    assert turn.allowed_tool_names == frozenset()


@pytest.mark.asyncio
async def test_role_metadata_does_not_change_device_bundle_selection():
    runtime = SkillTurnRuntime()
    original = bundle()

    first = await runtime.select(original, "杭州天气怎么样", RuleOnlyClassifier())
    runtime.set_role_metadata({"agentId": "agent-b", "prompt": "另一种角色"})
    second = await runtime.select(original, "杭州天气怎么样", RuleOnlyClassifier())

    assert first.bundle is original
    assert second.bundle is original
    assert second.bundle.device_id == "device-a"


def test_skill_prompt_is_injected_only_into_the_turn_messages():
    messages = [
        {"role": "system", "content": "你是一个陪伴机器人。"},
        {"role": "user", "content": "杭州天气怎么样"},
    ]
    skill = bundle().skills[0]

    routed = SkillTurnRuntime().inject_execution_prompt(messages, skill)

    assert messages[0]["content"] == "你是一个陪伴机器人。"
    assert "先确认地点，再查询真实天气。" in routed[0]["content"]
    assert routed[1] == messages[1]


def test_tool_defaults_are_split_between_arguments_and_plugin_config():
    tool = bundle().tools["get_weather"]
    function = {
        "type": "function",
        "function": {
            "name": "get_weather",
            "parameters": {"properties": {"location": {"type": "string"}}},
        },
    }

    prepared = SkillTurnRuntime().prepare_tool_call(
        tool, {"location": "上海"}, function
    )

    assert prepared.arguments == {"location": "上海"}
    assert prepared.config == {
        "api_host": "weather.example.com",
        "api_key_secret_id": "secret-weather",
    }


def test_invalid_enum_argument_falls_back_to_configured_skill_default():
    runtime = SkillTurnRuntime()
    tool = type(
        "Tool",
        (),
        {
            "defaults": {
                "source": "IT之家",
                "news_sources": "IT之家;36氪;Hacker News",
            }
        },
    )()
    description = {
        "type": "function",
        "function": {
            "parameters": {
                "properties": {
                    "source": {
                        "type": "string",
                        "enum": ["IT之家", "36氪", "Hacker News"],
                    }
                }
            }
        },
    }

    prepared = runtime.prepare_tool_call(
        tool,
        {"source": "科技新闻"},
        description,
    )

    assert prepared.arguments == {"source": "IT之家"}
    assert prepared.config == {"news_sources": "IT之家;36氪;Hacker News"}


def test_inferred_enum_argument_uses_default_when_user_did_not_name_it():
    runtime = SkillTurnRuntime()
    tool = type("Tool", (), {"defaults": {"source": "IT之家"}})()
    description = {
        "type": "function",
        "function": {
            "parameters": {
                "properties": {
                    "source": {
                        "type": "string",
                        "enum": ["IT之家", "36氪", "Hacker News"],
                    }
                }
            }
        },
    }

    prepared = runtime.prepare_tool_call(
        tool,
        {"source": "36氪"},
        description,
        utterance="给我测试科技快报",
    )

    assert prepared.arguments == {"source": "IT之家"}


def test_explicit_enum_argument_overrides_the_configured_default():
    runtime = SkillTurnRuntime()
    tool = type("Tool", (), {"defaults": {"source": "IT之家"}})()
    description = {
        "type": "function",
        "function": {
            "parameters": {
                "properties": {
                    "source": {
                        "type": "string",
                        "enum": ["IT之家", "36氪", "Hacker News"],
                    }
                }
            }
        },
    }

    prepared = runtime.prepare_tool_call(
        tool,
        {"source": "36氪"},
        description,
        utterance="给我一条36氪新闻",
    )

    assert prepared.arguments == {"source": "36氪"}


def load_method(path, class_name, method_name, namespace=None):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    class_node = next(
        node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == class_name
    )
    method = next(
        node
        for node in class_node.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name == method_name
    )
    namespace = {
        "Any": Any,
        "Dict": Dict,
        "List": List,
        "Optional": Optional,
        **dict(namespace or {}),
    }
    exec(
        compile(ast.fix_missing_locations(ast.Module(body=[method], type_ignores=[])), str(path), "exec"),
        namespace,
    )
    return namespace[method_name]


def load_optional_method(path, class_name, method_name, namespace=None):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    class_node = next(
        node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == class_name
    )
    method = next(
        (
            node
            for node in class_node.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name == method_name
        ),
        None,
    )
    if method is None:
        return None
    namespace = {
        "Any": Any,
        "Dict": Dict,
        "List": List,
        "Optional": Optional,
        **dict(namespace or {}),
    }
    exec(
        compile(ast.fix_missing_locations(ast.Module(body=[method], type_ignores=[])), str(path), "exec"),
        namespace,
    )
    return namespace[method_name]


def test_tool_manager_filters_descriptions_by_exact_allowed_names():
    get_descriptions = load_method(
        TOOL_MANAGER_PATH, "ToolManager", "get_function_descriptions"
    )

    class Definition:
        def __init__(self, name):
            self.description = {"type": "function", "function": {"name": name}}

    class Manager:
        _cached_function_descriptions = None

        def get_all_tools(self):
            return {
                "handle_exit_intent": Definition("handle_exit_intent"),
                "get_weather": Definition("get_weather"),
                "web_search": Definition("web_search"),
            }

    descriptions = get_descriptions(Manager(), {"handle_exit_intent", "get_weather"})

    assert [item["function"]["name"] for item in descriptions] == [
        "handle_exit_intent",
        "get_weather",
    ]


def test_connection_uses_the_turn_allowlist_for_model_tools():
    select_functions = load_method(
        CONNECTION_PATH, "ConnectionHandler", "_select_functions_for_query"
    )

    class Handler:
        def __init__(self):
            self.allowed = None

        def get_functions(self, allowed_names=None):
            self.allowed = allowed_names
            return [
                {"type": "function", "function": {"name": name}}
                for name in sorted(allowed_names or ())
            ]

    class Connection:
        config = {}
        read_config_from_api = True
        llm = type("LLM", (), {"tools_enabled": True})()
        _skill_turn = type(
            "Turn",
            (),
            {"allowed_tool_names": frozenset({"handle_exit_intent", "get_weather"})},
        )()

        def __init__(self):
            self.func_handler = Handler()

    conn = Connection()
    selected = select_functions(conn, "随便一段不会参与授权的文本")

    assert conn.func_handler.allowed == frozenset(
        {"handle_exit_intent", "get_weather"}
    )
    assert [item["function"]["name"] for item in selected] == [
        "get_weather",
        "handle_exit_intent",
    ]


def test_required_single_tool_skill_forces_only_that_tool_on_first_model_call():
    skill_tool_choice = load_optional_method(
        CONNECTION_PATH, "ConnectionHandler", "_skill_tool_choice"
    )
    prepare_functions = load_optional_method(
        CONNECTION_PATH,
        "ConnectionHandler",
        "_prepare_model_functions",
        {"DIRECT_ANSWER_TOOL": {"type": "function", "function": {"name": "direct_answer"}}},
    )

    assert skill_tool_choice is not None, "Selected Skill tool choice policy is missing"
    assert prepare_functions is not None, "Model function preparation policy is missing"

    current_bundle = bundle()

    class Handler:
        def get_functions(self, _allowed_names=None):
            return [
                {"type": "function", "function": {"name": "handle_exit_intent"}},
                {"type": "function", "function": {"name": "get_weather"}},
                {"type": "function", "function": {"name": "web_search"}},
            ]

    conn = type(
        "Connection",
        (),
        {
            "intent_type": "function_call",
            "func_handler": Handler(),
            "_skill_turn": type(
                "Turn",
                (),
                {
                    "bundle": current_bundle,
                    "skill": current_bundle.skills[0],
                    "allowed_tool_names": frozenset({"handle_exit_intent", "get_weather"}),
                },
            )(),
            "_select_functions_for_query": lambda self, _query: self.func_handler.get_functions(),
        },
    )()
    conn._skill_tool_choice = skill_tool_choice.__get__(conn)

    functions, tool_choice = prepare_functions(conn, "今天天气怎么样", 0, False)

    assert [item["function"]["name"] for item in functions] == ["get_weather"]
    assert tool_choice == {
        "type": "function",
        "function": {"name": "get_weather"},
    }


def test_direct_chat_keeps_direct_answer_without_forcing_a_tool():
    skill_tool_choice = load_optional_method(
        CONNECTION_PATH, "ConnectionHandler", "_skill_tool_choice"
    )
    prepare_functions = load_optional_method(
        CONNECTION_PATH,
        "ConnectionHandler",
        "_prepare_model_functions",
        {"DIRECT_ANSWER_TOOL": {"type": "function", "function": {"name": "direct_answer"}}},
    )

    assert skill_tool_choice is not None
    assert prepare_functions is not None

    class Handler:
        def get_functions(self, _allowed_names=None):
            return [{"type": "function", "function": {"name": "handle_exit_intent"}}]

    conn = type(
        "Connection",
        (),
        {
            "intent_type": "function_call",
            "func_handler": Handler(),
            "_skill_turn": None,
            "_select_functions_for_query": lambda self, _query: self.func_handler.get_functions(),
        },
    )()
    conn._skill_tool_choice = skill_tool_choice.__get__(conn)

    functions, tool_choice = prepare_functions(conn, "陪我聊聊天", 0, False)

    assert [item["function"]["name"] for item in functions] == [
        "handle_exit_intent",
        "direct_answer",
    ]
    assert tool_choice is None


def test_expired_or_missing_backend_bundle_fails_closed():
    select_functions = load_method(
        CONNECTION_PATH, "ConnectionHandler", "_select_functions_for_query"
    )

    class Handler:
        def get_functions(self, allowed_names=None):
            names = allowed_names or {"get_weather", "web_search"}
            return [
                {"type": "function", "function": {"name": name}}
                for name in sorted(names)
            ]

    conn = type(
        "Connection",
        (),
        {
            "config": {
                "plugins": {"get_weather": {"api_key": "stale-key"}}
            },
            "read_config_from_api": True,
            "llm": type("LLM", (), {"tools_enabled": True})(),
            "func_handler": Handler(),
            "_skill_turn": None,
        },
    )()

    selected = select_functions(conn, "查询天气")

    assert [item["function"]["name"] for item in selected] == [
        "handle_exit_intent"
    ]


def test_selected_skill_timeout_controls_tool_execution_deadline():
    timeout = load_optional_method(
        CONNECTION_PATH, "ConnectionHandler", "_skill_tool_timeout_seconds"
    )

    assert timeout is not None, "Skill timeout policy is missing from the conversation runtime"

    conn = type(
        "Connection",
        (),
        {
            "config": {"tool_call_timeout": 30},
            "_skill_turn": type(
                "Turn", (), {"skill": type("Skill", (), {"timeout_ms": 2500})()}
            )(),
        },
    )()

    assert timeout(conn) == 2.5


def test_fixed_response_skill_uses_tool_reply_without_another_llm_call():
    class Action(Enum):
        RESPONSE = 2
        REQLLM = 3

    apply_policy = load_optional_method(
        CONNECTION_PATH,
        "ConnectionHandler",
        "_apply_skill_response_policy",
        {"Action": Action},
    )

    assert apply_policy is not None, "Skill response policy is missing from the conversation runtime"

    result = type(
        "Result",
        (),
        {"action": Action.REQLLM, "result": "亮度已经调整", "response": None},
    )()
    conn = type(
        "Connection",
        (),
        {
            "_skill_turn": type(
                "Turn", (), {"skill": type("Skill", (), {"response_mode": "FIXED"})()}
            )(),
        },
    )()

    applied = apply_policy(conn, result)

    assert applied is result
    assert result.action == Action.RESPONSE
    assert result.response == "亮度已经调整"


@pytest.mark.asyncio
async def test_handler_resolves_secret_into_plugin_config_without_model_arguments():
    requested = []

    async def resolve_secret(device_id, secret_id):
        requested.append((device_id, secret_id))
        return "decrypted-key"

    prepare = load_method(
        ROOT / "core/providers/tools/unified_tool_handler.py",
        "UnifiedToolHandler",
        "_prepare_skill_arguments",
        {
            "SkillTurnRuntime": SkillTurnRuntime,
            "get_capability_secret": resolve_secret,
        },
    )
    current_bundle = bundle()

    class Definition:
        description = {
            "type": "function",
            "function": {
                "name": "get_weather",
                "parameters": {
                    "properties": {"location": {"type": "string"}}
                },
            },
        }

    class Manager:
        def get_all_tools(self):
            return {"get_weather": Definition()}

    handler = type(
        "Handler",
        (),
        {
            "conn": type(
                "Connection",
                (),
                {
                    "device_id": "device-a",
                    "_skill_turn": type("Turn", (), {"bundle": current_bundle})(),
                },
            )(),
            "config": {},
            "tool_manager": Manager(),
        },
    )()

    arguments = await prepare(handler, "get_weather", {"location": "上海"})

    assert arguments == {"location": "上海"}
    assert requested == [("device-a", "secret-weather")]
    assert handler.config["plugins"]["get_weather"] == {
        "api_host": "weather.example.com",
        "api_key": "decrypted-key",
    }


def test_skill_debug_details_exclude_prompt_secret_and_user_text():
    details_method = load_method(
        CONNECTION_PATH, "ConnectionHandler", "_skill_event_details"
    )
    skill = bundle().skills[0]
    conn = type(
        "Connection",
        (),
        {"_skill_turn": type("Turn", (), {"trigger_mode": "RULE"})()},
    )()

    details = details_method(conn, skill, result_class="REQLLM")

    assert set(details) == {
        "skillId",
        "publishedVersion",
        "triggerMode",
        "toolNames",
        "resultClass",
    }
    assert skill.execution_prompt not in str(details)
    assert "secret-weather" not in str(details)


def test_unified_tool_handler_logs_do_not_expose_arguments_or_exception_secrets(monkeypatch):
    from plugins_func.register import Action, ActionResponse
    from core.providers.tools.unified_tool_manager import ToolManager

    logged = []

    class Logger:
        def debug(self, message):
            logged.append(str(message))

        def warning(self, message):
            logged.append(str(message))

        def error(self, message):
            logged.append(str(message))

    class Manager:
        fail = False

        async def execute_tool(self, _name, _arguments):
            if self.fail:
                raise RuntimeError("token=exception-secret")
            return ActionResponse(Action.RESPONSE, "ok", None)

    async def ignore_display(*_args, **_kwargs):
        return None

    handle = load_method(
        ROOT / "core/providers/tools/unified_tool_handler.py",
        "UnifiedToolHandler",
        "handle_llm_function_call",
        {
            "json": __import__("json"),
            "Action": Action,
            "ActionResponse": ActionResponse,
            "ToolManager": ToolManager,
            "send_display_message": ignore_display,
        },
    )
    handler = type("Handler", (), {})()
    handler.conn = type("Connection", (), {"_skill_turn": None, "read_config_from_api": False})()
    handler.config = {}
    handler.logger = Logger()
    handler.tool_manager = Manager()
    handler._is_function_allowed = lambda _name: True

    async def prepare(_name, arguments):
        return dict(arguments)

    handler._prepare_skill_arguments = prepare

    asyncio.run(handle(handler,
        handler.conn, {"name": "weather", "arguments": {"token": "argument-secret"}},
    ))
    handler.tool_manager.fail = True
    asyncio.run(handle(handler,
        handler.conn, {"name": "weather", "arguments": {"city": "上海"}},
    ))

    rendered = " ".join(logged).lower()
    assert "argument-secret" not in rendered
    assert "exception-secret" not in rendered


def test_weather_news_and_search_are_not_connection_keyword_gates():
    source = CONNECTION_PATH.read_text(encoding="utf-8")
    method = ast.get_source_segment(
        source,
        next(
            node
            for node in next(
                item
                for item in ast.parse(source).body
                if isinstance(item, ast.ClassDef) and item.name == "ConnectionHandler"
            ).body
            if isinstance(node, ast.FunctionDef) and node.name == "_select_functions_for_query"
        ),
    )

    assert '"天气"' not in method
    assert '"新闻"' not in method
    assert '"搜索"' not in method


def test_device_tool_snapshot_contains_only_sanitized_names_and_schemas():
    payload = build_device_tool_snapshot(
        [
            {
                "type": "function",
                "function": {
                    "name": "self_screen_set_brightness",
                    "description": "调整屏幕亮度",
                    "parameters": {
                        "type": "object",
                        "properties": {"brightness": {"type": "integer"}},
                        "required": ["brightness"],
                    },
                },
            }
        ],
        device_model="plush-v2",
        firmware_version="1.3.0",
    )

    assert payload == {
        "deviceModel": "plush-v2",
        "firmwareVersion": "1.3.0",
        "tools": [
            {
                "name": "self_screen_set_brightness",
                "inputSchema": {
                    "type": "object",
                    "properties": {"brightness": {"type": "integer"}},
                    "required": ["brightness"],
                },
                "available": True,
            }
        ],
    }
