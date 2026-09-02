import unittest

from config.config_schema import ConfigValidationError, validate_config
from config.config_sources import ConfigSource, resolve_config


class ConfigSourcesTest(unittest.TestCase):
    def test_sources_are_merged_in_fixed_order_and_tracked(self):
        effective, source_map = resolve_config(
            [
                ConfigSource("request_override", 99, {"value": "request"}),
                ConfigSource("built_in_defaults", 0, {"value": "default", "nested": {"x": 1}}),
                ConfigSource("runtime_bundle", 30, {"nested": {"x": 3}}),
                ConfigSource("local_override", 10, {"value": "local"}),
                ConfigSource("manager_api_server", 20, {"value": "api", "nested": {"y": 2}}),
            ]
        )

        self.assertEqual("request", effective["value"])
        self.assertEqual({"x": 3, "y": 2}, effective["nested"])
        self.assertEqual("request_override", source_map["value"])
        self.assertEqual("runtime_bundle", source_map["nested.x"])
        self.assertEqual("manager_api_server", source_map["nested.y"])

    def test_explicit_local_transport_values_are_not_overwritten_by_api(self):
        effective, source_map = resolve_config(
            [
                ConfigSource("built_in_defaults", 0, {"server": {"websocket": "ws://default"}}),
                ConfigSource(
                    "local_override",
                    10,
                    {"server": {"websocket": "ws://local", "port": 8100}},
                ),
                ConfigSource(
                    "manager_api_server",
                    20,
                    {"server": {"websocket": "ws://api", "port": 8200}},
                ),
            ]
        )

        self.assertEqual("ws://local", effective["server"]["websocket"])
        self.assertEqual(8100, effective["server"]["port"])
        self.assertEqual("local_override", source_map["server.websocket"])

    def test_unknown_provider_keys_are_preserved(self):
        effective, _ = resolve_config(
            [
                ConfigSource(
                    "built_in_defaults",
                    0,
                    {"LLM": {"provider": {"type": "x", "vendor_key": "default"}}},
                ),
                ConfigSource(
                    "local_override",
                    10,
                    {"LLM": {"provider": {"vendor_key": "custom", "future": True}}},
                ),
            ]
        )

        self.assertEqual("custom", effective["LLM"]["provider"]["vendor_key"])
        self.assertTrue(effective["LLM"]["provider"]["future"])

    def test_invalid_required_server_field_is_rejected(self):
        with self.assertRaises(ConfigValidationError):
            validate_config({"server": {"ip": "0.0.0.0", "port": 0}})


if __name__ == "__main__":
    unittest.main()
