import asyncio
import importlib
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from loguru import logger

config_logger = importlib.import_module("config.logger")
mem0ai = None
ReportOnlyMemory = None
NoMemory = None
powermem = None


class ExternalMemoryClearTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        global mem0ai, ReportOnlyMemory, NoMemory, powermem
        with patch.object(config_logger, "setup_logging", return_value=logger):
            try:
                mem0ai = importlib.import_module(
                    "core.providers.memory.mem0ai.mem0ai"
                )
            except ModuleNotFoundError as error:
                if error.name != "mem0":
                    raise
                mem0_module = types.ModuleType("mem0")
                mem0_module.MemoryClient = MagicMock
                with patch.dict(sys.modules, {"mem0": mem0_module}):
                    mem0ai = importlib.import_module(
                        "core.providers.memory.mem0ai.mem0ai"
                    )
            ReportOnlyMemory = importlib.import_module(
                "core.providers.memory.mem_report_only.mem_report_only"
            ).MemoryProvider
            NoMemory = importlib.import_module(
                "core.providers.memory.nomem.nomem"
            ).MemoryProvider
            powermem = importlib.import_module(
                "core.providers.memory.powermem.powermem"
            )

    async def run_isolated_import(self, script):
        with tempfile.TemporaryDirectory() as directory:
            bootstrap = f"""
from core.utils.cache.manager import cache_manager, CacheType
cache_manager.set(CacheType.CONFIG, 'main_config', {{
    'log': {{'log_level': 'ERROR', 'log_dir': {directory!r}, 'data_dir': {directory!r}}}
}})
"""
            return await asyncio.to_thread(
                subprocess.run,
                [sys.executable, "-c", bootstrap + script],
                capture_output=True,
                text=True,
            )

    def make_mem0(self, *, enabled=True, client=True, namespace="companion:test"):
        provider = object.__new__(mem0ai.MemoryProvider)
        provider.use_mem0 = enabled
        provider.memory_namespace = namespace
        if client:
            provider.client = MagicMock()
        return provider

    def make_powermem(
        self,
        *,
        enabled=True,
        client=True,
        namespace="companion:test",
        user_profile=False,
    ):
        provider = object.__new__(powermem.MemoryProvider)
        provider.use_powermem = enabled
        provider.memory_namespace = namespace
        provider.memory_client = MagicMock() if client else None
        provider.enable_user_profile = user_profile
        provider.last_profile_content = "cached profile"
        return provider

    async def test_mem0_returns_false_when_disabled(self):
        provider = self.make_mem0(enabled=False)

        self.assertFalse(await provider.clear_memory())

    async def test_mem0_returns_false_without_client(self):
        provider = self.make_mem0(client=False)

        self.assertFalse(await provider.clear_memory())

    async def test_mem0_returns_false_without_namespace(self):
        provider = self.make_mem0(namespace=None)

        self.assertFalse(await provider.clear_memory())

    async def test_mem0_deletes_namespace_in_thread(self):
        provider = self.make_mem0()

        with patch.object(mem0ai.asyncio, "to_thread", new_callable=AsyncMock) as to_thread:
            self.assertTrue(await provider.clear_memory())

        to_thread.assert_awaited_once_with(
            provider.client.delete_all,
            user_id="companion:test",
        )

    async def test_mem0_logs_and_returns_false_when_delete_raises(self):
        provider = self.make_mem0()
        provider.client.delete_all.side_effect = RuntimeError("delete failed")
        bound_logger = MagicMock()

        with patch.object(mem0ai.logger, "bind", return_value=bound_logger):
            result = await provider.clear_memory()

        self.assertFalse(result)
        bound_logger.error.assert_called_once()

    async def test_powermem_returns_false_when_disabled(self):
        provider = self.make_powermem(enabled=False)

        self.assertFalse(await provider.clear_memory())

    async def test_powermem_returns_false_without_client(self):
        provider = self.make_powermem(client=False)

        self.assertFalse(await provider.clear_memory())

    async def test_powermem_returns_false_without_namespace(self):
        provider = self.make_powermem(namespace=None)

        self.assertFalse(await provider.clear_memory())

    async def test_powermem_returns_false_without_delete_all(self):
        provider = self.make_powermem()
        del provider.memory_client.delete_all

        self.assertFalse(await provider.clear_memory())

    async def test_powermem_user_memory_delete_runs_in_thread_and_clears_profile(self):
        provider = self.make_powermem(user_profile=True)

        with patch.object(
            powermem.asyncio,
            "to_thread",
            new=AsyncMock(return_value=True),
        ) as to_thread:
            self.assertTrue(await provider.clear_memory())

        to_thread.assert_awaited_once_with(
            provider.memory_client.delete_all,
            user_id="companion:test",
            delete_profile=True,
        )
        self.assertEqual("", provider.last_profile_content)

    async def test_powermem_async_delete_returns_true(self):
        provider = self.make_powermem()
        provider.memory_client.delete_all = AsyncMock(return_value=True)

        self.assertTrue(await provider.clear_memory())
        provider.memory_client.delete_all.assert_awaited_once_with(
            user_id="companion:test"
        )

    async def test_powermem_sync_false_result_preserves_profile_cache(self):
        provider = self.make_powermem(user_profile=True)

        with patch.object(
            powermem.asyncio,
            "to_thread",
            new=AsyncMock(return_value=False),
        ):
            result = await provider.clear_memory()

        self.assertFalse(result)
        self.assertEqual("cached profile", provider.last_profile_content)

    async def test_powermem_async_false_result_returns_false(self):
        provider = self.make_powermem()
        provider.memory_client.delete_all = AsyncMock(return_value=False)

        self.assertFalse(await provider.clear_memory())

    async def test_powermem_none_result_is_not_success(self):
        provider = self.make_powermem()
        provider.memory_client.delete_all = AsyncMock(return_value=None)

        self.assertFalse(await provider.clear_memory())

    async def test_powermem_logs_and_returns_false_when_sync_delete_raises(self):
        provider = self.make_powermem(user_profile=True)
        provider.memory_client.delete_all.side_effect = RuntimeError("delete failed")
        bound_logger = MagicMock()

        with patch.object(powermem.logger, "bind", return_value=bound_logger):
            result = await provider.clear_memory()

        self.assertFalse(result)
        bound_logger.error.assert_called_once()
        self.assertEqual("cached profile", provider.last_profile_content)

    async def test_powermem_logs_and_returns_false_when_async_delete_raises(self):
        provider = self.make_powermem()
        provider.memory_client.delete_all = AsyncMock(
            side_effect=RuntimeError("delete failed")
        )
        bound_logger = MagicMock()

        with patch.object(powermem.logger, "bind", return_value=bound_logger):
            result = await provider.clear_memory()

        self.assertFalse(result)
        bound_logger.error.assert_called_once()

    async def test_no_memory_clear_remains_successful(self):
        self.assertTrue(await NoMemory({}).clear_memory())

    async def test_report_only_clear_remains_successful(self):
        self.assertTrue(await ReportOnlyMemory({}).clear_memory())

    async def test_importing_test_module_does_not_poison_later_connection_import(self):
        script = """
import sys
import tests.test_external_memory_clear
assert 'mem0' not in sys.modules
assert hasattr(sys.modules['config.logger'], 'create_connection_logger')
import core.connection
"""

        result = await self.run_isolated_import(script)

        self.assertEqual(0, result.returncode, result.stderr)

    async def test_importing_test_module_preserves_preloaded_mem0_module(self):
        script = """
import sys
import types
sentinel = types.ModuleType('mem0')
sentinel.MemoryClient = object
sys.modules['mem0'] = sentinel
import tests.test_external_memory_clear
assert sys.modules['mem0'] is sentinel
"""

        result = await self.run_isolated_import(script)

        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
