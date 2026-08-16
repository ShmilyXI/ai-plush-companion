import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from core.providers.tools.unified_tool_handler import UnifiedToolHandler
from plugins_func.register import all_function_registry


class FakeLogger:
    def debug(self, *_args, **_kwargs):
        pass

    def info(self, *_args, **_kwargs):
        pass

    def warning(self, *_args, **_kwargs):
        pass

    def error(self, *_args, **_kwargs):
        pass


class FakeToolManager:
    def __init__(self):
        self.cached_names = list(all_function_registry)
        self.refresh_count = 0

    def refresh_tools(self):
        self.cached_names = list(all_function_registry)
        self.refresh_count += 1


class UnifiedToolInitializationTest(unittest.TestCase):
    def setUp(self):
        self.original_registry = dict(all_function_registry)

    def tearDown(self):
        all_function_registry.clear()
        all_function_registry.update(self.original_registry)

    def test_initialization_refreshes_tools_after_plugins_are_imported(self):
        all_function_registry.clear()
        handler = object.__new__(UnifiedToolHandler)
        handler.logger = FakeLogger()
        handler.tool_manager = FakeToolManager()
        handler.server_mcp_executor = SimpleNamespace(
            initialize=lambda: asyncio.sleep(0)
        )
        handler.finish_init = False
        handler.current_support_functions = lambda: handler.tool_manager.cached_names
        handler._initialize_mcp_endpoint = lambda: asyncio.sleep(0)
        handler._initialize_home_assistant = lambda: None

        def import_weather(_package_name):
            all_function_registry["get_weather"] = object()

        with patch(
            "core.providers.tools.unified_tool_handler.auto_import_modules",
            import_weather,
        ):
            asyncio.run(handler._initialize())

        self.assertEqual(1, handler.tool_manager.refresh_count)
        self.assertIn("get_weather", handler.tool_manager.cached_names)


if __name__ == "__main__":
    unittest.main()
