import asyncio
import unittest

from plugins_func.loadplugins import load_plugin_registry
from plugins_func.manifest import PluginManifest, validate_manifests
from plugins_func.register import FunctionItem, FunctionRegistry, ToolType


class PluginRegistryTest(unittest.TestCase):
    def test_manifest_validation_rejects_duplicates_and_public_scope_gaps(self):
        manifests = [
            PluginManifest("weather", "plugins.weather", "SERVER_PLUGIN", "PLUGIN", "public", "weather"),
            PluginManifest("weather", "plugins.other", "SERVER_PLUGIN", "PLUGIN", "public", "weather"),
        ]
        with self.assertRaises(ValueError):
            validate_manifests(manifests)

        with self.assertRaises(ValueError):
            validate_manifests(
                [PluginManifest("weather", "plugins.weather", "SERVER_PLUGIN", "PLUGIN", None, "weather")]
            )

    def test_registry_order_is_deterministic_and_isolated(self):
        first = FunctionRegistry()
        second = FunctionRegistry()
        item = FunctionItem("b", {}, lambda: None, ToolType.WAIT)
        first.register_function("b", item)
        first.register_function("a", FunctionItem("a", {}, lambda: None, ToolType.WAIT))

        self.assertEqual(["a", "b"], sorted(first))
        self.assertIsNone(second.get_function("b"))

    def test_disposers_are_called_once(self):
        calls = []
        registry = FunctionRegistry()
        registry.add_disposer(lambda: calls.append("closed"))
        registry.add_disposer(lambda: calls.append("closed-2"))

        asyncio.run(registry.dispose())
        asyncio.run(registry.dispose())

        self.assertEqual(["closed-2", "closed"], calls)

    def test_loader_accepts_explicit_manifest_and_keeps_registry_scope(self):
        registry = load_plugin_registry(
            [
                PluginManifest(
                    "get_weather",
                    "plugins_func.functions.get_weather",
                    "SERVER_PLUGIN",
                    "PLUGIN",
                    "public",
                    "get_weather",
                )
            ]
        )

        self.assertIn("get_weather", registry)
        self.assertNotIn("play_music", registry)


if __name__ == "__main__":
    unittest.main()
