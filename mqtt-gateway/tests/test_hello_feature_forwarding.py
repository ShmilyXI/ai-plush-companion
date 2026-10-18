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

    def test_remote_bridge_drop_closes_stale_mqtt_session(self):
        source = (ROOT / "app.js").read_text(encoding="utf-8")

        self.assertIn("this.closeRequested = false;", source)
        self.assertIn("this.closeRequested = true;", source)
        self.assertIn("const bridge = new WebSocketBridge", source)
        self.assertIn(
            "if (!this.closing && !bridge.closeRequested && !this.server.callManager.isInCall(this.macAddress))",
            source,
        )
        self.assertIn("this.protocol.close();", source)

    def test_liveness_is_boolean(self):
        source = (ROOT / "app.js").read_text(encoding="utf-8")

        self.assertIn(
            "return Boolean(this.wsClient && this.wsClient.readyState === WebSocket.OPEN);",
            source,
        )
        self.assertIn(
            "return Boolean(this.bridge && this.bridge.isAlive());",
            source,
        )


if __name__ == "__main__":
    unittest.main()
