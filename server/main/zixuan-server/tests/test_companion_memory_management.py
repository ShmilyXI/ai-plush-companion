import asyncio
import json
import multiprocessing
import os
import sys
import tempfile
import threading
import time
import types
import unittest
import uuid
from unittest.mock import AsyncMock, Mock, patch

import yaml
from aiohttp import web
from aiohttp.test_utils import make_mocked_request
from loguru import logger


logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda *args, **kwargs: logger
sys.modules.setdefault("config.logger", logger_module)
mem0_module = types.ModuleType("mem0")
mem0_module.MemoryClient = object
sys.modules.setdefault("mem0", mem0_module)

from core.api.companion_memory_handler import CompanionMemoryHandler
from core.providers.memory.mem_local_short.mem_local_short import MemoryProvider
from core.providers.memory.mem0ai.mem0ai import MemoryProvider as Mem0Provider
from core.providers.memory.powermem.powermem import MemoryProvider as PowerMemProvider
from core.providers.memory.powermem import powermem as powermem_module


def _multiprocess_update_worker(memory_path, namespace, memory_id, content, start_event):
    provider = MemoryProvider({}, None)
    provider.memory_path = memory_path
    provider.init_memory(namespace, llm=None, save_to_file=True)
    original_read = provider._read_all_memory_locked

    def slow_read():
        result = original_read()
        time.sleep(0.1)
        return result

    provider._read_all_memory_locked = slow_read
    start_event.wait()
    asyncio.run(provider.update_memory_item(memory_id, content))


class LocalMemoryManagementTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.memory_path = os.path.join(self.temp_dir.name, ".memory.yaml")
        self.namespace = "companion:" + "a" * 64
        self.provider = MemoryProvider({}, None)
        self.provider.memory_path = self.memory_path
        self.provider.init_memory(self.namespace, llm=None, save_to_file=True)

    def tearDown(self):
        self.temp_dir.cleanup()

    async def test_replace_lists_stable_items_then_updates_and_deletes_one(self):
        await self.provider.replace_memory_items(["喜欢草莓", "住在杭州"])

        first = await self.provider.list_memory_items()
        second = await self.provider.list_memory_items()

        self.assertEqual(first, second)
        self.assertEqual(["喜欢草莓", "住在杭州"], [item["content"] for item in first])
        self.assertTrue(all(item["id"] and item["updated_at"] for item in first))

        self.assertTrue(await self.provider.update_memory_item(first[0]["id"], "喜欢蓝莓"))
        self.assertEqual("喜欢蓝莓", (await self.provider.list_memory_items())[0]["content"])

        self.assertTrue(await self.provider.delete_memory_item(first[1]["id"]))
        remaining = await self.provider.list_memory_items()
        self.assertEqual([first[0]["id"]], [item["id"] for item in remaining])

    async def test_clear_removes_only_selected_namespace_from_disk(self):
        other_namespace = "companion:" + "b" * 64
        with open(self.memory_path, "w", encoding="utf-8") as stream:
            yaml.safe_dump(
                {self.namespace: "A", other_namespace: "B"},
                stream,
                allow_unicode=True,
            )

        self.provider.init_memory(
            self.namespace,
            llm=None,
            summary_memory="A",
            save_to_file=False,
        )
        self.assertTrue(await self.provider.clear_memory())

        reloaded = MemoryProvider({}, None)
        reloaded.memory_path = self.memory_path
        reloaded.init_memory(self.namespace, llm=None, save_to_file=True)
        other = MemoryProvider({}, None)
        other.memory_path = self.memory_path
        other.init_memory(other_namespace, llm=None, save_to_file=True)
        self.assertEqual("", await reloaded.query_memory("anything"))
        self.assertEqual("B", await other.query_memory("anything"))
        with open(self.memory_path, "r", encoding="utf-8") as stream:
            stored = yaml.safe_load(stream)
        self.assertEqual(
            {"version": 1, "summary": "", "items": []}, stored[self.namespace]
        )
        self.assertEqual("B", stored[other_namespace])

    async def test_api_backed_summary_uses_stable_namespace_item_id(self):
        first = MemoryProvider({}, "A")
        first.init_memory(
            self.namespace, llm=None, summary_memory="A", save_to_file=False,
            source_metadata={"source_device_id": "device-a", "source_profile_id": "profile-a"},
        )
        second = MemoryProvider({}, "A")
        second.init_memory(
            self.namespace, llm=None, summary_memory="A", save_to_file=False,
            source_metadata={"source_device_id": "device-a", "source_profile_id": "profile-b"},
        )

        first_item = (await first.list_memory_items())[0]
        second_item = (await second.list_memory_items())[0]
        self.assertEqual(first_item["id"], second_item["id"])
        self.assertIsNone(first_item["source_device_id"])
        self.assertIsNone(first_item["source_profile_id"])
        self.assertIsNone(second_item["source_device_id"])
        self.assertIsNone(second_item["source_profile_id"])

    async def test_legacy_scalar_remains_unknown_across_profile_connections(self):
        with open(self.memory_path, "w", encoding="utf-8") as stream:
            yaml.safe_dump({self.namespace: "旧记忆"}, stream, allow_unicode=True)

        items = []
        for profile_id in ("profile-a", "profile-b"):
            provider = MemoryProvider({}, None)
            provider.memory_path = self.memory_path
            provider.init_memory(
                self.namespace, llm=None, save_to_file=True,
                source_metadata={"source_device_id": "device-a", "source_profile_id": profile_id},
            )
            items.append((await provider.list_memory_items())[0])

        self.assertEqual(items[0]["id"], items[1]["id"])
        self.assertTrue(all(item["source_device_id"] is None for item in items))
        self.assertTrue(all(item["source_profile_id"] is None for item in items))

    async def test_legacy_list_and_record_items_without_sources_remain_unknown(self):
        legacy_values = [
            ["列表记忆", {"content": "字典记忆"}],
            {"version": 3, "summary": "记录记忆", "items": [{"content": "记录记忆"}]},
        ]
        for stored in legacy_values:
            with self.subTest(stored=stored):
                with open(self.memory_path, "w", encoding="utf-8") as stream:
                    yaml.safe_dump({self.namespace: stored}, stream, allow_unicode=True)
                provider = MemoryProvider({}, None)
                provider.memory_path = self.memory_path
                provider.init_memory(
                    self.namespace, llm=None, save_to_file=True,
                    source_metadata={"source_device_id": "device-a", "source_profile_id": "profile-a"},
                )
                items = await provider.list_memory_items()
                self.assertTrue(all(item["source_device_id"] is None for item in items))
                self.assertTrue(all(item["source_profile_id"] is None for item in items))

    async def test_newly_saved_local_memory_uses_trusted_current_source(self):
        class NewMemoryLLM:
            model_name = "new-memory-test"
            api_key = "test"

            def response_no_stream(self, *args, **kwargs):
                return '{"memory":"新记忆"}'

        provider = MemoryProvider({}, None)
        provider.memory_path = self.memory_path
        provider.init_memory(
            self.namespace, llm=NewMemoryLLM(), save_to_file=True,
            source_metadata={"source_device_id": "device-a", "source_profile_id": "profile-a"},
        )
        messages = [
            types.SimpleNamespace(role="user", content="hello"),
            types.SimpleNamespace(role="assistant", content="hi"),
        ]

        await provider.save_memory(messages)

        item = (await provider.list_memory_items())[0]
        self.assertEqual("device-a", item["source_device_id"])
        self.assertEqual("profile-a", item["source_profile_id"])

    async def test_updating_legacy_scalar_preserves_unknown_source(self):
        with open(self.memory_path, "w", encoding="utf-8") as stream:
            yaml.safe_dump({self.namespace: "旧记忆"}, stream, allow_unicode=True)
        provider = MemoryProvider({}, None)
        provider.memory_path = self.memory_path
        provider.init_memory(
            self.namespace, llm=None, save_to_file=True,
            source_metadata={"source_device_id": "device-a", "source_profile_id": "profile-a"},
        )
        memory_id = (await provider.list_memory_items())[0]["id"]

        self.assertTrue(await provider.update_memory_item(memory_id, "纠正后的旧记忆"))

        item = (await provider.list_memory_items())[0]
        self.assertIsNone(item["source_device_id"])
        self.assertIsNone(item["source_profile_id"])

    async def test_two_instances_update_different_items_without_lost_update(self):
        await self.provider.replace_memory_items(["A", "B"])
        first = MemoryProvider({}, None)
        first.memory_path = self.memory_path
        first.init_memory(self.namespace, llm=None, save_to_file=True)
        second = MemoryProvider({}, None)
        second.memory_path = self.memory_path
        second.init_memory(self.namespace, llm=None, save_to_file=True)
        item_ids = [item["id"] for item in await first.list_memory_items()]
        barrier = threading.Barrier(2)

        def update(provider, memory_id, content):
            barrier.wait()
            asyncio.run(provider.update_memory_item(memory_id, content))

        threads = [
            threading.Thread(target=update, args=(first, item_ids[0], "A1")),
            threading.Thread(target=update, args=(second, item_ids[1], "B1")),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=2)

        reloaded = MemoryProvider({}, None)
        reloaded.memory_path = self.memory_path
        reloaded.init_memory(self.namespace, llm=None, save_to_file=True)
        self.assertEqual(["A1", "B1"], [item["content"] for item in await reloaded.list_memory_items()])

    async def test_stale_instance_cannot_resurrect_cleared_namespace(self):
        await self.provider.replace_memory_items(["A"])
        stale = MemoryProvider({}, None)
        stale.memory_path = self.memory_path
        stale.init_memory(self.namespace, llm=None, save_to_file=True)
        memory_id = (await stale.list_memory_items())[0]["id"]

        self.assertTrue(await self.provider.clear_memory())

        self.assertFalse(await stale.update_memory_item(memory_id, "resurrected"))
        with open(self.memory_path, "r", encoding="utf-8") as stream:
            stored = (yaml.safe_load(stream) or {})[self.namespace]
        self.assertEqual("", stored["summary"])
        self.assertEqual([], stored["items"])

    async def test_concurrent_updates_preserve_different_namespaces(self):
        other_namespace = "companion:" + "f" * 64
        await self.provider.replace_memory_items(["A"])
        other = MemoryProvider({}, None)
        other.memory_path = self.memory_path
        other.init_memory(other_namespace, llm=None, save_to_file=True)
        await other.replace_memory_items(["B"])
        first_id = (await self.provider.list_memory_items())[0]["id"]
        second_id = (await other.list_memory_items())[0]["id"]
        barrier = threading.Barrier(2)

        def update(provider, memory_id, content):
            barrier.wait()
            asyncio.run(provider.update_memory_item(memory_id, content))

        threads = [
            threading.Thread(target=update, args=(self.provider, first_id, "A1")),
            threading.Thread(target=update, args=(other, second_id, "B1")),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=2)

        first = MemoryProvider({}, None)
        first.memory_path = self.memory_path
        first.init_memory(self.namespace, llm=None, save_to_file=True)
        second = MemoryProvider({}, None)
        second.memory_path = self.memory_path
        second.init_memory(other_namespace, llm=None, save_to_file=True)
        self.assertEqual("A1", (await first.list_memory_items())[0]["content"])
        self.assertEqual("B1", (await second.list_memory_items())[0]["content"])

    async def test_multiprocess_updates_do_not_overwrite_same_namespace(self):
        await self.provider.replace_memory_items(["A", "B"])
        item_ids = [item["id"] for item in await self.provider.list_memory_items()]
        context = multiprocessing.get_context("fork")
        start_event = context.Event()
        processes = [
            context.Process(
                target=_multiprocess_update_worker,
                args=(self.memory_path, self.namespace, item_ids[0], "A1", start_event),
            ),
            context.Process(
                target=_multiprocess_update_worker,
                args=(self.memory_path, self.namespace, item_ids[1], "B1", start_event),
            ),
        ]
        for process in processes:
            process.start()
        start_event.set()
        for process in processes:
            process.join(timeout=3)
            self.assertEqual(0, process.exitcode)

        reloaded = MemoryProvider({}, None)
        reloaded.memory_path = self.memory_path
        reloaded.init_memory(self.namespace, llm=None, save_to_file=True)
        self.assertEqual(["A1", "B1"], [item["content"] for item in await reloaded.list_memory_items()])

    async def test_late_llm_summary_cannot_resurrect_cleared_namespace(self):
        await self.provider.replace_memory_items(["A"])
        started = threading.Event()
        release = threading.Event()

        class BlockingLLM:
            model_name = "blocking-test"
            api_key = "test"

            def response_no_stream(self, *args, **kwargs):
                started.set()
                release.wait(timeout=2)
                return '{"memory":"late"}'

        stale = MemoryProvider({}, None)
        stale.memory_path = self.memory_path
        stale.init_memory(self.namespace, llm=BlockingLLM(), save_to_file=True)
        messages = [
            types.SimpleNamespace(role="user", content="hello"),
            types.SimpleNamespace(role="assistant", content="hi"),
        ]

        save_thread = threading.Thread(target=lambda: asyncio.run(stale.save_memory(messages)))
        save_thread.start()
        self.assertTrue(started.wait(timeout=1))
        self.assertTrue(await self.provider.clear_memory())
        release.set()
        save_thread.join(timeout=3)

        reloaded = MemoryProvider({}, None)
        reloaded.memory_path = self.memory_path
        reloaded.init_memory(self.namespace, llm=None, save_to_file=True)
        self.assertEqual([], await reloaded.list_memory_items())

    async def test_loaded_snapshot_rejects_summary_started_after_clear(self):
        await self.provider.replace_memory_items(["A"])

        class ImmediateLLM:
            model_name = "immediate-test"
            api_key = "test"

            def response_no_stream(self, *args, **kwargs):
                return '{"memory":"stale"}'

        stale = MemoryProvider({}, None)
        stale.memory_path = self.memory_path
        stale.init_memory(self.namespace, llm=ImmediateLLM(), save_to_file=True)
        self.assertTrue(await self.provider.clear_memory())

        messages = [
            types.SimpleNamespace(role="user", content="hello"),
            types.SimpleNamespace(role="assistant", content="hi"),
        ]
        self.assertEqual("", await stale.save_memory(messages))

        reloaded = MemoryProvider({}, None)
        reloaded.memory_path = self.memory_path
        reloaded.init_memory(self.namespace, llm=None, save_to_file=True)
        self.assertEqual([], await reloaded.list_memory_items())

    async def test_file_backed_load_prefers_versioned_disk_snapshot_over_summary_hint(self):
        await self.provider.replace_memory_items(["disk"])

        reloaded = MemoryProvider({}, None)
        reloaded.memory_path = self.memory_path
        reloaded.init_memory(
            self.namespace,
            llm=None,
            summary_memory="stale hint",
            save_to_file=True,
        )

        self.assertEqual("disk", await reloaded.query_memory("anything"))
        self.assertEqual(self.provider._snapshot_version, reloaded._snapshot_version)

    async def test_loaded_snapshot_rejects_summary_started_after_update(self):
        await self.provider.replace_memory_items(["A"])

        class ImmediateLLM:
            model_name = "immediate-test"
            api_key = "test"

            def response_no_stream(self, *args, **kwargs):
                return '{"memory":"stale"}'

        stale = MemoryProvider({}, None)
        stale.memory_path = self.memory_path
        stale.init_memory(self.namespace, llm=ImmediateLLM(), save_to_file=True)
        memory_id = (await self.provider.list_memory_items())[0]["id"]
        self.assertTrue(await self.provider.update_memory_item(memory_id, "B"))

        messages = [
            types.SimpleNamespace(role="user", content="hello"),
            types.SimpleNamespace(role="assistant", content="hi"),
        ]
        self.assertEqual("B", await stale.save_memory(messages))

        reloaded = MemoryProvider({}, None)
        reloaded.memory_path = self.memory_path
        reloaded.init_memory(self.namespace, llm=None, save_to_file=True)
        self.assertEqual(["B"], [item["content"] for item in await reloaded.list_memory_items()])

    async def test_primary_yaml_record_owns_version_and_migrates_legacy_value(self):
        other_namespace = "companion:" + "b" * 64
        with open(self.memory_path, "w", encoding="utf-8") as stream:
            yaml.safe_dump(
                {self.namespace: "A", other_namespace: ["legacy"]},
                stream,
                allow_unicode=True,
            )
        self.provider.init_memory(self.namespace, llm=None, save_to_file=True)

        await self.provider.replace_memory_items(["A1"])

        with open(self.memory_path, "r", encoding="utf-8") as stream:
            stored = yaml.safe_load(stream)
        self.assertEqual(1, stored[self.namespace]["version"])
        self.assertEqual("A1", stored[self.namespace]["summary"])
        self.assertEqual(["A1"], [item["content"] for item in stored[self.namespace]["items"]])
        self.assertEqual(["legacy"], stored[other_namespace])
        self.assertFalse(os.path.exists(self.memory_path + ".versions.yaml"))

    async def test_legacy_list_dict_without_id_keeps_stable_management_id(self):
        fixed_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{self.namespace}:0"))
        with open(self.memory_path, "w", encoding="utf-8") as stream:
            yaml.safe_dump(
                {
                    self.namespace: [
                        {"content": "legacy"},
                        {"id": fixed_id, "content": "fixed"},
                    ]
                },
                stream,
                allow_unicode=True,
            )
        self.provider.init_memory(self.namespace, llm=None, save_to_file=True)
        items = await self.provider.list_memory_items()
        memory_id = items[0]["id"]
        self.assertEqual(2, len({item["id"] for item in items}))

        self.assertTrue(await self.provider.update_memory_item(memory_id, "updated"))

        reloaded = MemoryProvider({}, None)
        reloaded.memory_path = self.memory_path
        reloaded.init_memory(self.namespace, llm=None, save_to_file=True)
        self.assertEqual(
            [(memory_id, "updated"), (fixed_id, "fixed")],
            [(item["id"], item["content"]) for item in await reloaded.list_memory_items()],
        )
        self.assertTrue(await reloaded.delete_memory_item(fixed_id))
        self.assertEqual(
            [(memory_id, "updated")],
            [(item["id"], item["content"]) for item in await reloaded.list_memory_items()],
        )

    async def test_versioned_dict_missing_ids_are_stable_across_reload_put_and_delete(self):
        fixed_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{self.namespace}:0"))
        with open(self.memory_path, "w", encoding="utf-8") as stream:
            yaml.safe_dump(
                {
                    self.namespace: {
                        "version": 7,
                        "summary": "duplicate\nduplicate\nfixed",
                        "items": [
                            {"content": "duplicate"},
                            {"content": "duplicate"},
                            {"id": fixed_id, "content": "fixed"},
                        ],
                    }
                },
                stream,
                allow_unicode=True,
            )
        self.provider.init_memory(self.namespace, llm=None, save_to_file=True)
        first_items = await self.provider.list_memory_items()

        reloaded = MemoryProvider({}, None)
        reloaded.memory_path = self.memory_path
        reloaded.init_memory(self.namespace, llm=None, save_to_file=True)
        second_items = await reloaded.list_memory_items()

        first_ids = [item["id"] for item in first_items]
        self.assertEqual(first_ids, [item["id"] for item in second_items])
        self.assertEqual(3, len(set(first_ids)))
        self.assertEqual(fixed_id, first_ids[2])
        self.assertTrue(await reloaded.update_memory_item(first_ids[0], "updated"))

        delete_provider = MemoryProvider({}, None)
        delete_provider.memory_path = self.memory_path
        delete_provider.init_memory(self.namespace, llm=None, save_to_file=True)
        self.assertTrue(await delete_provider.delete_memory_item(first_ids[1]))
        self.assertEqual(
            [(first_ids[0], "updated"), (fixed_id, "fixed")],
            [
                (item["id"], item["content"])
                for item in await delete_provider.list_memory_items()
            ],
        )

    async def test_legacy_sidecar_is_migrated_into_primary_yaml_and_removed(self):
        cleared_namespace = "companion:" + "c" * 64
        with open(self.memory_path, "w", encoding="utf-8") as stream:
            yaml.safe_dump({self.namespace: "A"}, stream, allow_unicode=True)
        with open(self.memory_path + ".versions.yaml", "w", encoding="utf-8") as stream:
            yaml.safe_dump(
                {self.namespace: 4, cleared_namespace: 2},
                stream,
                allow_unicode=True,
            )

        self.provider.init_memory(self.namespace, llm=None, save_to_file=True)

        with open(self.memory_path, "r", encoding="utf-8") as stream:
            stored = yaml.safe_load(stream)
        self.assertEqual(4, stored[self.namespace]["version"])
        self.assertEqual("A", stored[self.namespace]["summary"])
        self.assertEqual(2, stored[cleared_namespace]["version"])
        self.assertEqual([], stored[cleared_namespace]["items"])
        self.assertFalse(os.path.exists(self.memory_path + ".versions.yaml"))

    async def test_atomic_write_failure_preserves_old_record_and_snapshot_version(self):
        await self.provider.replace_memory_items(["A"])
        with open(self.memory_path, "rb") as stream:
            original = stream.read()
        original_version = self.provider._snapshot_version

        self.provider.memory_items = [self.provider._new_item("B")]
        self.provider._sync_summary()
        with patch(
            "core.providers.memory.mem_local_short.mem_local_short.os.replace",
            side_effect=OSError("replace failed"),
        ):
            with self.assertRaises(OSError):
                self.provider.save_memory_to_file(expected_version=original_version)

        with open(self.memory_path, "rb") as stream:
            self.assertEqual(original, stream.read())
        self.assertEqual(original_version, self.provider._snapshot_version)
        reloaded = MemoryProvider({}, None)
        reloaded.memory_path = self.memory_path
        reloaded.init_memory(self.namespace, llm=None, save_to_file=True)
        self.assertEqual(["A"], [item["content"] for item in await reloaded.list_memory_items()])

    async def test_file_fsync_failure_preserves_old_record_and_success_fsyncs_directory(self):
        await self.provider.replace_memory_items(["A"])
        with open(self.memory_path, "rb") as stream:
            original = stream.read()
        original_version = self.provider._snapshot_version

        self.provider.memory_items = [self.provider._new_item("B")]
        self.provider._sync_summary()
        with patch(
            "core.providers.memory.mem_local_short.mem_local_short.os.fsync",
            side_effect=OSError("fsync failed"),
        ):
            with self.assertRaises(OSError):
                self.provider.save_memory_to_file(expected_version=original_version)

        with open(self.memory_path, "rb") as stream:
            self.assertEqual(original, stream.read())
        self.assertEqual(original_version, self.provider._snapshot_version)

        with patch(
            "core.providers.memory.mem_local_short.mem_local_short.os.fsync",
            wraps=os.fsync,
        ) as fsync:
            self.provider.save_memory_to_file(expected_version=original_version)
        self.assertEqual(2, fsync.call_count)


class CompanionMemoryHandlerTest(unittest.IsolatedAsyncioTestCase):
    def make_handler(self, provider):
        return CompanionMemoryHandler(
            {"server": {"auth_key": "secret"}, "read_config_from_api": True},
            config_loader=AsyncMock(
                return_value={
                    "companion_identity": {
                        "user_id": 7,
                        "agent_id": "agent-id",
                        "device_id": "device-id",
                        "memory_namespace": "companion:" + "c" * 64,
                    }
                }
            ),
            memory_factory=Mock(return_value=provider),
        )

    def request(self, method, body=None, query=""):
        request = make_mocked_request(
            method,
            "/internal/companion-memory" + query,
            headers={"Authorization": "Bearer secret"},
        )
        if body is not None:
            request._read_bytes = json.dumps(body).encode()
        return request

    async def test_lists_without_accepting_public_namespace(self):
        provider = Mock()
        provider.list_memory_items = AsyncMock(
            return_value=[{"id": "m1", "content": "记忆", "updated_at": "2026-01-01T00:00:00Z"}]
        )
        handler = self.make_handler(provider)

        response = await handler.handle_get(
            self.request("GET", query="?mac_address=AA%3ABB&client_id=manager-api&namespace=evil")
        )

        self.assertEqual(200, response.status)
        self.assertNotIn("namespace", response.text)
        provider.list_memory_items.assert_awaited_once()

    async def test_provider_receives_trusted_source_identity_not_public_body(self):
        provider = Mock()
        provider.list_memory_items = AsyncMock(return_value=[])
        handler = self.make_handler(provider)

        await handler.handle_get(self.request(
            "GET",
            query="?mac_address=AA%3ABB&source_device_id=evil&source_profile_id=evil",
        ))

        self.assertEqual(
            {
                "source_user_id": 7,
                "source_device_id": "device-id",
                "source_profile_id": "agent-id",
            },
            handler.memory_factory.call_args.kwargs["source_metadata"],
        )

    async def test_local_memory_keeps_source_when_profile_switches(self):
        with tempfile.TemporaryDirectory() as directory:
            memory_path = os.path.join(directory, ".memory.yaml")
            namespace = "companion:" + "f" * 64
            provider = MemoryProvider({}, None)
            provider.memory_path = memory_path
            provider.init_memory(
                namespace,
                llm=None,
                save_to_file=True,
                source_metadata={"source_device_id": "device-a", "source_profile_id": "profile-a"},
            )
            await provider.replace_memory_items(["记忆"])

            switched = MemoryProvider({}, None)
            switched.memory_path = memory_path
            switched.init_memory(
                namespace,
                llm=None,
                save_to_file=True,
                source_metadata={"source_device_id": "device-a", "source_profile_id": "profile-b"},
            )

            item = (await switched.list_memory_items())[0]
            self.assertEqual("profile-a", item["source_profile_id"])

    async def test_updates_and_deletes_single_item(self):
        provider = Mock()
        provider.update_memory_item = AsyncMock(return_value=True)
        provider.delete_memory_item = AsyncMock(return_value=True)
        handler = self.make_handler(provider)

        update = await handler.handle_put(
            self.request(
                "PUT",
                {"mac_address": "AA:BB", "client_id": "manager-api", "memory_id": "m1", "content": "新内容"},
            )
        )
        deleted = await handler.handle_delete(
            self.request(
                "DELETE",
                {"mac_address": "AA:BB", "client_id": "manager-api", "memory_id": "m1"},
            )
        )

        self.assertEqual(200, update.status)
        self.assertEqual(200, deleted.status)
        provider.update_memory_item.assert_awaited_once_with("m1", "新内容")
        provider.delete_memory_item.assert_awaited_once_with("m1")

    async def test_provider_failure_is_not_reported_as_success(self):
        provider = Mock()
        provider.update_memory_item = AsyncMock(return_value=False)
        handler = self.make_handler(provider)

        with self.assertRaises(web.HTTPBadGateway):
            await handler.handle_put(
                self.request(
                    "PUT",
                    {"mac_address": "AA:BB", "memory_id": "m1", "content": "新内容"},
                )
            )

    async def test_rejects_blank_or_oversized_content(self):
        handler = self.make_handler(Mock())
        for content in ("   ", "x" * 4001):
            with self.subTest(length=len(content)):
                with self.assertRaises(web.HTTPBadRequest):
                    await handler.handle_put(
                        self.request(
                            "PUT",
                            {"mac_address": "AA:BB", "memory_id": "m1", "content": content},
                        )
                    )

    async def test_api_mode_full_clear_removes_only_resolved_local_yaml_namespace(self):
        with tempfile.TemporaryDirectory() as directory:
            memory_path = os.path.join(directory, ".memory.yaml")
            namespace = "companion:" + "d" * 64
            other_namespace = "companion:" + "e" * 64
            with open(memory_path, "w", encoding="utf-8") as stream:
                yaml.safe_dump({namespace: "A", other_namespace: "B"}, stream)

            def memory_factory(config, resolved_namespace, save_to_file, source_metadata=None):
                provider = MemoryProvider({}, config.get("summaryMemory"))
                provider.memory_path = memory_path
                provider.init_memory(
                    resolved_namespace,
                    llm=None,
                    summary_memory=config.get("summaryMemory"),
                    save_to_file=save_to_file,
                    source_metadata=source_metadata,
                )
                return provider

            handler = CompanionMemoryHandler(
                {"server": {"auth_key": "secret"}, "read_config_from_api": True},
                config_loader=AsyncMock(
                    return_value={
                        "summaryMemory": "A",
                        "companion_identity": {
                            "user_id": 7,
                            "agent_id": "agent-id",
                            "device_id": "device-id",
                            "memory_namespace": namespace,
                        },
                    }
                ),
                memory_factory=memory_factory,
            )

            response = await handler.handle_delete(
                self.request("DELETE", {"mac_address": "AA:BB"})
            )

            self.assertEqual(200, response.status)
            cleared = MemoryProvider({}, None)
            cleared.memory_path = memory_path
            cleared.init_memory(namespace, llm=None, save_to_file=True)
            other = MemoryProvider({}, None)
            other.memory_path = memory_path
            other.init_memory(other_namespace, llm=None, save_to_file=True)
            self.assertEqual("", await cleared.query_memory("anything"))
            self.assertEqual("B", await other.query_memory("anything"))
            with open(memory_path, "r", encoding="utf-8") as stream:
                stored = yaml.safe_load(stream)
            self.assertEqual(
                {"version": 1, "summary": "", "items": []}, stored[namespace]
            )
            self.assertEqual("B", stored[other_namespace])

    async def test_delete_rejects_invalid_memory_id_before_resolving_provider(self):
        memory_factory = Mock()
        handler = CompanionMemoryHandler(
            {"server": {"auth_key": "secret"}, "read_config_from_api": True},
            config_loader=AsyncMock(),
            memory_factory=memory_factory,
        )
        invalid_ids = (None, "", "   ", {}, [], "x" * 257)

        for memory_id in invalid_ids:
            with self.subTest(memory_id=memory_id):
                with self.assertRaises(web.HTTPBadRequest):
                    await handler.handle_delete(
                        self.request(
                            "DELETE",
                            {"mac_address": "AA:BB", "memory_id": memory_id},
                        )
                    )

        memory_factory.assert_not_called()

    async def test_merge_migration_deduplicates_and_preserves_source(self):
        class Provider:
            def __init__(self, items):
                self.items = [dict(item) for item in items]

            async def list_memory_items(self):
                return [dict(item) for item in self.items]

            async def add_memory_item(self, content, source_metadata=None):
                self.items.append({"id": f"new-{len(self.items)}", "content": content, **(source_metadata or {})})
                return True

            async def clear_memory(self):
                self.items.clear()
                return True

        providers = {
            "source": Provider([{"id": "s1", "content": "天气提醒"}, {"id": "s2", "content": "新内容"}]),
            "target": Provider([{"id": "t1", "content": "天气提醒"}]),
        }
        async def loader(_config, device_id, _client):
            return {"companion_identity": {"user_id": 7, "agent_id": "agent-id",
                    "device_id": device_id, "memory_namespace": "companion:" +
                    ("a" * 63) + ("1" if device_id == "source" else "2")}}
        handler = CompanionMemoryHandler(
            {"server": {"auth_key": "secret"}, "read_config_from_api": True},
            config_loader=loader,
            memory_factory=lambda _config, namespace, _save, source_metadata=None: providers[
                "source" if namespace.endswith("1") else "target"],
        )

        response = await handler.handle_migration(self.request("POST", {
            "source_mac_address": "source", "target_mac_address": "target", "mode": "merge",
        }))

        self.assertEqual(200, response.status)
        payload = json.loads(response.text)
        self.assertEqual(1, payload["imported_count"])
        self.assertEqual(1, payload["skipped_count"])
        self.assertEqual(2, len(providers["source"].items))
        self.assertEqual(2, len(providers["target"].items))

    async def test_merge_retry_is_idempotent_after_partial_provider_failure(self):
        class Provider:
            def __init__(self, items, fail_once=False):
                self.items = [dict(item) for item in items]
                self.fail_once = fail_once

            async def list_memory_items(self):
                return [dict(item) for item in self.items]

            async def add_memory_item(self, content, source_metadata=None):
                if self.fail_once:
                    self.fail_once = False
                    return False
                self.items.append({"id": f"new-{len(self.items)}", "content": content,
                                   **(source_metadata or {})})
                return True

            async def clear_memory(self):
                self.items.clear()
                return True

        providers = {
            "source": Provider([{"id": "s1", "content": "提醒一"},
                                 {"id": "s2", "content": "提醒二"}]),
            "target": Provider([], fail_once=True),
        }

        async def loader(_config, device_id, _client):
            return {"companion_identity": {"user_id": 7, "agent_id": "agent-id",
                    "device_id": device_id, "memory_namespace": "companion:" +
                    ("a" * 63) + ("1" if device_id == "source" else "2")}}

        handler = CompanionMemoryHandler(
            {"server": {"auth_key": "secret"}, "read_config_from_api": True},
            config_loader=loader,
            memory_factory=lambda _config, namespace, _save, source_metadata=None: providers[
                "source" if namespace.endswith("1") else "target"],
        )

        first = await handler.handle_migration(self.request("POST", {
            "source_mac_address": "source", "target_mac_address": "target", "mode": "merge",
        }))
        self.assertEqual(502, first.status)

        second = await handler.handle_migration(self.request("POST", {
            "source_mac_address": "source", "target_mac_address": "target", "mode": "merge",
        }))
        self.assertEqual(200, second.status)
        payload = json.loads(second.text)
        self.assertEqual(2, payload["imported_count"])
        self.assertEqual(0, payload["skipped_count"])

        third = await handler.handle_migration(self.request("POST", {
            "source_mac_address": "source", "target_mac_address": "target", "mode": "merge",
        }))
        self.assertEqual(200, third.status)
        retry_payload = json.loads(third.text)
        self.assertEqual(0, retry_payload["imported_count"])
        self.assertEqual(2, retry_payload["skipped_count"])
        self.assertEqual(2, len(providers["target"].items))

    async def test_overwrite_failure_restores_target_and_is_not_success(self):
        class Provider:
            def __init__(self, items, fail_add=False):
                self.items = [dict(item) for item in items]
                self.fail_add = fail_add

            async def list_memory_items(self):
                return [dict(item) for item in self.items]

            async def clear_memory(self):
                self.items.clear()
                return True

            async def add_memory_item(self, content, source_metadata=None):
                if self.fail_add:
                    self.fail_add = False
                    return False
                self.items.append({"id": f"new-{len(self.items)}", "content": content})
                return True

        providers = {"source": Provider([{"id": "s1", "content": "源内容"}]),
                     "target": Provider([{"id": "t1", "content": "目标内容"}], fail_add=True)}
        async def loader(_config, device_id, _client):
            return {"companion_identity": {"user_id": 7, "agent_id": "agent-id",
                    "device_id": device_id, "memory_namespace": "companion:" +
                    ("a" * 63) + ("1" if device_id == "source" else "2")}}
        handler = CompanionMemoryHandler(
            {"server": {"auth_key": "secret"}, "read_config_from_api": True},
            config_loader=loader,
            memory_factory=lambda _config, namespace, _save, source_metadata=None: providers[
                "source" if namespace.endswith("1") else "target"],
        )

        response = await handler.handle_migration(self.request("POST", {
            "source_mac_address": "source", "target_mac_address": "target", "mode": "overwrite",
        }))

        self.assertEqual(502, response.status)
        payload = json.loads(response.text)
        self.assertFalse(payload["success"])
        self.assertTrue(payload["retryable"])
        self.assertTrue(payload["recovered"])
        self.assertEqual(["目标内容"], [item["content"] for item in providers["target"].items])


class ExternalProviderManagementTest(unittest.IsolatedAsyncioTestCase):
    async def test_mem0_uses_list_update_and_delete_sdk_operations(self):
        provider = Mem0Provider.__new__(Mem0Provider)
        provider.use_mem0 = True
        provider.memory_namespace = "companion:test"
        provider.role_id = provider.memory_namespace
        provider.source_metadata = {
            "source_device_id": "device-current",
            "source_profile_id": "profile-current",
        }
        provider.client = Mock()
        provider.client.get_all.return_value = {
            "results": [{"id": "m1", "memory": "内容", "updated_at": "2026-01-01"}]
        }
        provider.client.update.return_value = {"message": "ok"}
        provider.client.delete.return_value = {"message": "ok"}

        self.assertEqual(
            [{
                "id": "m1", "content": "内容", "updated_at": "2026-01-01",
                "source_device_id": None, "source_profile_id": None,
            }],
            await provider.list_memory_items(),
        )
        self.assertTrue(await provider.update_memory_item("m1", "新内容"))
        self.assertTrue(await provider.delete_memory_item("m1"))
        self.assertFalse(await provider.delete_memory_item("foreign"))
        provider.client.update.assert_called_once_with("m1", "新内容")
        provider.client.delete.assert_called_once_with("m1")

    async def test_powermem_does_not_backfill_current_source_for_legacy_items(self):
        provider = PowerMemProvider.__new__(PowerMemProvider)
        provider.use_powermem = True
        provider.enable_user_profile = False
        provider.memory_namespace = "companion:test"
        provider.role_id = provider.memory_namespace
        provider.source_metadata = {
            "source_device_id": "device-current",
            "source_profile_id": "profile-current",
        }
        provider.memory_client = Mock()
        provider.memory_client.get_all = AsyncMock(
            return_value={"results": [{"id": "m1", "memory": "旧记忆"}]}
        )

        item = (await provider.list_memory_items())[0]

        self.assertIsNone(item["source_device_id"])
        self.assertIsNone(item["source_profile_id"])

    async def test_powermem_update_falls_back_to_delete_then_add(self):
        provider = PowerMemProvider.__new__(PowerMemProvider)
        provider.use_powermem = True
        provider.enable_user_profile = False
        provider.memory_namespace = "companion:test"
        provider.role_id = provider.memory_namespace
        provider.memory_client = Mock()
        provider.memory_client.get_all = AsyncMock(
            return_value={"results": [{"id": "m1", "memory": "内容", "updated_at": "2026-01-01"}]}
        )
        provider.memory_client.delete = AsyncMock(return_value=True)
        provider.memory_client.add = AsyncMock(
            return_value={"results": [{"id": "m2", "memory": "新内容", "event": "ADD"}]}
        )
        provider.memory_client.update = None

        self.assertEqual("m1", (await provider.list_memory_items())[0]["id"])
        self.assertTrue(await provider.update_memory_item("m1", "新内容"))
        provider.memory_client.delete.assert_awaited_once_with("m1")
        provider.memory_client.add.assert_awaited_once_with(
            messages=[{"role": "user", "content": "新内容"}],
            user_id="companion:test",
            infer=False,
        )

    async def test_powermem_fallback_update_keeps_original_source_metadata(self):
        provider = PowerMemProvider.__new__(PowerMemProvider)
        provider.use_powermem = True
        provider.enable_user_profile = False
        provider.memory_namespace = "companion:test"
        provider.role_id = provider.memory_namespace
        provider.source_metadata = {
            "source_device_id": "device-current",
            "source_profile_id": "profile-current",
        }
        provider.memory_client = Mock()
        provider.memory_client.get_all = AsyncMock(return_value={"results": [{
            "id": "m1", "memory": "内容",
            "metadata": {"source_device_id": "device-old", "source_profile_id": "profile-old"},
        }]})
        provider.memory_client.delete = AsyncMock(return_value=True)
        provider.memory_client.add = AsyncMock(
            return_value={"results": [{"id": "m2", "memory": "新内容", "event": "ADD"}]}
        )
        provider.memory_client.update = None

        self.assertTrue(await provider.update_memory_item("m1", "新内容"))
        self.assertEqual(
            {"source_device_id": "device-old", "source_profile_id": "profile-old"},
            provider.memory_client.add.await_args.kwargs["metadata"],
        )

    async def test_powermem_does_not_hide_failed_replacement(self):
        provider = PowerMemProvider.__new__(PowerMemProvider)
        provider.use_powermem = True
        provider.enable_user_profile = False
        provider.memory_namespace = "companion:test"
        provider.role_id = provider.memory_namespace
        provider.memory_client = Mock()
        provider.memory_client.get_all = AsyncMock(
            return_value={"results": [{"id": "m1", "memory": "内容"}]}
        )
        provider.memory_client.delete = AsyncMock(return_value=True)
        provider.memory_client.add = AsyncMock(
            side_effect=[
                False,
                {"results": [{"id": "restored", "memory": "内容", "event": "ADD"}]},
            ]
        )
        provider.memory_client.update = None

        self.assertFalse(await provider.update_memory_item("m1", "新内容"))
        self.assertEqual(
            ["新内容", "内容"],
            [call.kwargs["messages"][0]["content"] for call in provider.memory_client.add.await_args_list],
        )

    async def test_powermem_requires_created_results_and_restores_on_empty_add(self):
        for empty_result in ({"results": []}, {"results": [{}]}, [], {}):
            with self.subTest(empty_result=empty_result):
                provider = PowerMemProvider.__new__(PowerMemProvider)
                provider.use_powermem = True
                provider.enable_user_profile = False
                provider.memory_namespace = "companion:test"
                provider.role_id = provider.memory_namespace
                provider.memory_client = Mock()
                provider.memory_client.get_all = AsyncMock(
                    return_value={"results": [{"id": "m1", "memory": "旧内容"}]}
                )
                provider.memory_client.delete = AsyncMock(return_value=True)
                provider.memory_client.add = AsyncMock(
                    side_effect=[
                        empty_result,
                        {"results": [{"id": "restored", "memory": "旧内容", "event": "ADD"}]},
                    ]
                )
                provider.memory_client.update = None

                self.assertFalse(await provider.update_memory_item("m1", "新内容"))
                self.assertEqual(
                    ["新内容", "旧内容"],
                    [
                        call.kwargs["messages"][0]["content"]
                        for call in provider.memory_client.add.await_args_list
                    ],
                )

    async def test_powermem_restores_old_content_when_replacement_add_raises(self):
        provider = PowerMemProvider.__new__(PowerMemProvider)
        provider.use_powermem = True
        provider.enable_user_profile = False
        provider.memory_namespace = "companion:test"
        provider.role_id = provider.memory_namespace
        provider.memory_client = Mock()
        provider.memory_client.get_all = AsyncMock(
            return_value={"results": [{"id": "m1", "memory": "旧内容"}]}
        )
        provider.memory_client.delete = AsyncMock(return_value=True)
        provider.memory_client.add = AsyncMock(
            side_effect=[
                RuntimeError("add failed"),
                {"results": [{"id": "restored", "memory": "旧内容", "event": "ADD"}]},
            ]
        )
        provider.memory_client.update = None

        self.assertFalse(await provider.update_memory_item("m1", "新内容"))
        self.assertEqual(
            ["新内容", "旧内容"],
            [call.kwargs["messages"][0]["content"] for call in provider.memory_client.add.await_args_list],
        )

    async def test_powermem_only_accepts_events_that_leave_created_memory(self):
        invalid_events = ("DELETE", "NONE", "NOOP", "CREATE", "ARCHIVE")
        for event in invalid_events:
            with self.subTest(event=event):
                provider = PowerMemProvider.__new__(PowerMemProvider)
                provider.use_powermem = True
                provider.enable_user_profile = False
                provider.memory_namespace = "companion:test"
                provider.role_id = provider.memory_namespace
                provider.memory_client = Mock()
                provider.memory_client.get_all = AsyncMock(
                    return_value={"results": [{"id": "m1", "memory": "旧内容"}]}
                )
                provider.memory_client.delete = AsyncMock(return_value=True)
                provider.memory_client.add = AsyncMock(
                    side_effect=[
                        {"results": [{"id": "m2", "memory": "新内容", "event": event}]},
                        {"results": [{"id": "m3", "memory": "旧内容", "event": "ADD"}]},
                    ]
                )
                provider.memory_client.update = None

                self.assertFalse(await provider.update_memory_item("m1", "新内容"))
                self.assertEqual(2, provider.memory_client.add.await_count)
                self.assertTrue(
                    all(
                        call.kwargs["infer"] is False
                        for call in provider.memory_client.add.await_args_list
                    )
                )

        provider = PowerMemProvider.__new__(PowerMemProvider)
        provider.use_powermem = True
        provider.enable_user_profile = False
        provider.memory_namespace = "companion:test"
        provider.role_id = provider.memory_namespace
        provider.memory_client = Mock()
        provider.memory_client.get_all = AsyncMock(
            return_value={"results": [{"id": "m1", "memory": "旧内容"}]}
        )
        provider.memory_client.delete = AsyncMock(return_value=True)
        provider.memory_client.add = AsyncMock(
            return_value={"results": [{"id": "m2", "memory": "新内容", "event": "ADD"}]}
        )
        provider.memory_client.update = None

        self.assertTrue(await provider.update_memory_item("m1", "新内容"))

    async def test_powermem_logs_compensation_failure_without_memory_content(self):
        provider = PowerMemProvider.__new__(PowerMemProvider)
        provider.use_powermem = True
        provider.enable_user_profile = False
        provider.memory_namespace = "companion:test"
        provider.role_id = provider.memory_namespace
        provider.memory_client = Mock()
        provider.memory_client.get_all = AsyncMock(
            return_value={"results": [{"id": "m1", "memory": "private old content"}]}
        )
        provider.memory_client.delete = AsyncMock(return_value=True)
        provider.memory_client.add = AsyncMock(
            side_effect=[{"results": []}, {"results": []}]
        )
        provider.memory_client.update = None
        bound_logger = Mock()

        with patch.object(powermem_module.logger, "bind", return_value=bound_logger):
            self.assertFalse(await provider.update_memory_item("m1", "private new content"))

        bound_logger.error.assert_called_with("event=powermem_memory_compensation_failed")
        logged = " ".join(str(arg) for call in bound_logger.error.call_args_list for arg in call.args)
        self.assertNotIn("private old content", logged)
        self.assertNotIn("private new content", logged)


if __name__ == "__main__":
    unittest.main()
