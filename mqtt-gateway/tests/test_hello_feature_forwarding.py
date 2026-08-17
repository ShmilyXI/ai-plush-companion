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


if __name__ == "__main__":
    unittest.main()
