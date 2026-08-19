import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class HelloFeatureForwardingTest(unittest.TestCase):
    def test_gateway_remembers_device_features_and_forwards_them(self):
        source = (ROOT / "app.js").read_text(encoding="utf-8")

        self.assertIn("this.deviceFeatures = {};", source)
        self.assertIn("this.deviceFeatures = json.features || {};", source)
        self.assertIn("this.bridge.connect(json.audio_params, this.deviceFeatures)", source)
        self.assertIn("设备握手能力", source)

    def test_gateway_logs_device_abort_requests(self):
        source = (ROOT / "app.js").read_text(encoding="utf-8")

        self.assertIn("if (json.type === 'abort')", source)
        self.assertIn("收到设备打断请求", source)

    def test_gateway_syncs_server_vision_capabilities_to_device(self):
        source = (ROOT / "app.js").read_text(encoding="utf-8")

        self.assertIn("syncServerCapabilities", source)
        self.assertIn("await this.sendMcpRequest('initialize', params, 5000)", source)
        self.assertIn("params?.capabilities?.vision", source)
        self.assertIn("'initialize', 'notifications/initialized', 'tools/list'", source)

    def test_management_commands_allow_vision_model_latency(self):
        source = (ROOT / "app.js").read_text(encoding="utf-8")

        self.assertIn("targetConnection.sendMcpRequest(method, params, 60000)", source)


if __name__ == "__main__":
    unittest.main()
