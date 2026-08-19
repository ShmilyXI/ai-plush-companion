import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class StartLocalConfigSyncTest(unittest.TestCase):
    def test_startup_syncs_vision_url_to_runtime_config(self):
        source = (ROOT / "start-local.sh").read_text(encoding="utf-8")

        self.assertIn(
            'write_parameter server.vision_explain "http://$gateway_public_ip:8003/mcp/vision/explain"',
            source,
        )
        self.assertIn("s/^  vision_explain: .*$/", source)
        self.assertIn("server.vision_explain", source.split("HDEL", 1)[1])


if __name__ == "__main__":
    unittest.main()
