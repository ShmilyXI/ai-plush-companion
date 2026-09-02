import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class MqttActiveReconnectTest(unittest.TestCase):
    def source(self):
        return (ROOT / "main/protocols/mqtt_protocol.cc").read_text(encoding="utf-8")

    def test_reconnect_timer_runs_even_when_an_audio_channel_is_active(self):
        source = self.source()

        callback = source[source.index("reconnect_timer_args") : source.index("esp_timer_create(&reconnect_timer_args")]
        self.assertIn("StartMqttClient(false)", callback)
        self.assertNotIn("GetDeviceState() == kDeviceStateIdle", callback)

    def test_active_channel_reconnect_schedules_a_new_audio_channel(self):
        source = self.source()

        self.assertIn("reconnect_audio_channel_", source)
        self.assertIn("OpenAudioChannel()", source)
        self.assertIn("重新打开音频通道", source)


if __name__ == "__main__":
    unittest.main()
