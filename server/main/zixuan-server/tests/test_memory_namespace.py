import asyncio
import sys
import tempfile
import threading
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from loguru import logger
import yaml

logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda: logger
logger_module.build_module_string = lambda selected: "00000000000000"
logger_module.create_connection_logger = lambda selected: logger
sys.modules["config.logger"] = logger_module

from core.providers.memory.base import MemoryProviderBase
from core.providers.memory.mem_local_short.mem_local_short import MemoryProvider as LocalMemory
from core.utils.dialogue import Message
from core.utils.util import check_model_key


class FakeMemory(MemoryProviderBase):
    async def save_memory(self, msgs, session_id=None):
        return None

    async def query_memory(self, query):
        return ""


class KeylessLocalLlm:
    model_name = "local-test-model"

    def response_no_stream(self, *args, **kwargs):
        return '{"称呼":"阿宁"}'


class MemoryNamespaceTest(unittest.TestCase):
    def test_local_llm_without_api_key_does_not_fail_key_check(self):
        self.assertIsNone(check_model_key("本地记忆模型", None))

    def test_keyless_local_llm_saves_summary_to_disk(self):
        with tempfile.TemporaryDirectory() as directory:
            namespace = "companion:" + "a" * 64
            provider = LocalMemory({}, summary_memory=None)
            provider.memory_path = str(Path(directory) / "memory.yaml")
            provider.init_memory(namespace, llm=KeylessLocalLlm())

            result = asyncio.run(provider.save_memory([
                Message(role="user", content="叫我阿宁"),
                Message(role="assistant", content="好，我记住了"),
            ]))

            self.assertEqual('{"称呼":"阿宁"}', result)
            saved_memory = Path(provider.memory_path).read_text(encoding="utf-8")
            self.assertIn(namespace, saved_memory)

    def test_base_provider_uses_explicit_namespace(self):
        provider = FakeMemory({})
        provider.init_memory("companion:" + "a" * 64, llm=None)
        self.assertEqual("companion:" + "a" * 64, provider.memory_namespace)
        self.assertEqual(provider.memory_namespace, provider.role_id)

    def test_local_clear_removes_only_current_namespace(self):
        with tempfile.TemporaryDirectory() as directory:
            namespace = "companion:" + "a" * 64
            other_namespace = "companion:" + "b" * 64
            provider = LocalMemory({}, summary_memory=None)
            provider.memory_path = str(Path(directory) / "memory.yaml")
            Path(provider.memory_path).write_text(
                yaml.safe_dump(
                    {
                        namespace: '{"name":"Ada"}',
                        other_namespace: '{"name":"Lin"}',
                    },
                    allow_unicode=True,
                ),
                encoding="utf-8",
            )
            provider.init_memory(namespace, llm=None)
            provider.short_memory = '{"name":"Ada"}'
            self.assertTrue(asyncio.run(provider.clear_memory()))
            self.assertEqual("", provider.short_memory)

            reloaded = LocalMemory({}, summary_memory=None)
            reloaded.memory_path = provider.memory_path
            reloaded.init_memory(namespace, llm=None)
            self.assertEqual("", reloaded.short_memory)

            other = LocalMemory({}, summary_memory=None)
            other.memory_path = provider.memory_path
            other.init_memory(other_namespace, llm=None)
            self.assertEqual('{"name":"Lin"}', other.short_memory)

    def test_concurrent_saves_preserve_both_namespaces(self):
        with tempfile.TemporaryDirectory() as directory:
            memory_path = str(Path(directory) / "memory.yaml")
            Path(memory_path).write_text("{}\n", encoding="utf-8")
            provider_a = LocalMemory({}, summary_memory=None)
            provider_b = LocalMemory({}, summary_memory=None)
            provider_a.memory_path = memory_path
            provider_b.memory_path = memory_path
            provider_a.init_memory("companion:" + "a" * 64, llm=None)
            provider_b.init_memory("companion:" + "b" * 64, llm=None)
            provider_a.short_memory = '{"name":"A"}'
            provider_b.short_memory = '{"name":"B"}'
            barrier = threading.Barrier(2)
            real_safe_load = yaml.safe_load

            def synchronized_read(stream):
                data = real_safe_load(stream)
                try:
                    barrier.wait(timeout=0.2)
                except threading.BrokenBarrierError:
                    pass
                return data

            with patch(
                "core.providers.memory.mem_local_short.mem_local_short.yaml.safe_load",
                side_effect=synchronized_read,
            ):
                thread_a = threading.Thread(target=provider_a.save_memory_to_file)
                thread_b = threading.Thread(target=provider_b.save_memory_to_file)
                thread_a.start()
                thread_b.start()
                thread_a.join(timeout=2)
                thread_b.join(timeout=2)

            saved = yaml.safe_load(Path(memory_path).read_text(encoding="utf-8"))
            self.assertEqual(
                '{"name":"A"}', saved[provider_a.memory_namespace]["summary"]
            )
            self.assertEqual(
                '{"name":"B"}', saved[provider_b.memory_namespace]["summary"]
            )

    def test_reloading_missing_namespace_clears_stale_in_memory_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            namespace = "companion:" + "a" * 64
            provider = LocalMemory({}, summary_memory=None)
            provider.memory_path = str(Path(directory) / "memory.yaml")
            Path(provider.memory_path).write_text(
                f"'{namespace}': '{{\"测试代号\":\"松果\"}}'\n",
                encoding="utf-8",
            )
            provider.init_memory(namespace, llm=None)
            self.assertEqual('{"测试代号":"松果"}', provider.short_memory)

            Path(provider.memory_path).write_text("{}\n", encoding="utf-8")

            provider.init_memory(namespace, llm=None)

            self.assertEqual("", provider.short_memory)


if __name__ == "__main__":
    unittest.main()
