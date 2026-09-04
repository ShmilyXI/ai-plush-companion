import copy
import importlib
import sys
import types

from loguru import logger


logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
logger_module.build_module_string = lambda selected: "00000000000000"
logger_module.create_connection_logger = lambda selected: logger
sys.modules["config.logger"] = logger_module

tool_handler_module = types.ModuleType("core.providers.tools.unified_tool_handler")
tool_handler_module.UnifiedToolHandler = object
sys.modules["core.providers.tools.unified_tool_handler"] = tool_handler_module
plugin_loader_module = types.ModuleType("plugins_func.loadplugins")
plugin_loader_module.auto_import_modules = lambda *args, **kwargs: None
sys.modules["plugins_func.loadplugins"] = plugin_loader_module

from core.connection import ConnectionHandler


def teardown_module():
    sys.modules.pop("core.providers.tools.unified_tool_handler", None)
    importlib.import_module("core.providers.tools.unified_tool_handler")


def connection(words):
    config = {
        "exit_commands": [],
        "close_connection_no_voice_time": 120,
        "wakeup_words": words,
        "zixuan": {"audio_params": {"sample_rate": 24000}},
    }
    return ConnectionHandler(config, None, None, None, None, None)


def test_private_active_word_is_connection_local_and_keeps_global_defaults():
    first = connection(["你好紫萱"])
    second = connection(["你好紫萱"])

    first._apply_device_wakeup_words({"device_wakeup_words": ["小布小布", "小布小布"]})
    second._apply_device_wakeup_words({"device_wakeup_words": ["小云小云"]})

    assert first.config["wakeup_words"] == ["你好紫萱", "小布小布"]
    assert second.config["wakeup_words"] == ["你好紫萱", "小云小云"]
    assert first.common_config["wakeup_words"] == ["你好紫萱"]


def test_empty_private_words_leave_global_wake_words_available():
    conn = connection(["你好紫萱"])
    before = copy.deepcopy(conn.common_config)

    conn._apply_device_wakeup_words({"device_wakeup_words": []})

    assert conn.config["wakeup_words"] == ["你好紫萱"]
    assert conn.common_config == before
