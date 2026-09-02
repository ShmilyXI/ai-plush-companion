import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ProactiveRealtimeCapabilityTest(unittest.TestCase):
    def test_device_advertises_realtime_when_aec_is_available(self):
        for relative in ("main/protocols/mqtt_protocol.cc", "main/protocols/websocket_protocol.cc"):
            source = (ROOT / relative).read_text(encoding="utf-8")
            self.assertIn('"realtime"', source)

    def test_device_accepts_server_realtime_listening_request(self):
        source = (ROOT / "main/application.cc").read_text(encoding="utf-8")
        self.assertIn('strcmp(type->valuestring, "listen")', source)
        self.assertIn('strcmp(mode->valuestring, "realtime")', source)
        self.assertIn("SetListeningMode(kListeningModeRealtime)", source)


if __name__ == "__main__":
    unittest.main()
