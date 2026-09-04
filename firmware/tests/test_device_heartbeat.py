import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class DeviceHeartbeatTest(unittest.TestCase):
    def heartbeat_source(self):
        return (ROOT / "main/device_heartbeat.cc").read_text(encoding="utf-8")

    def test_heartbeat_uses_ota_base_and_device_header(self):
        source = self.heartbeat_source()

        self.assertIn('Settings settings("wifi", false)', source)
        self.assertIn('settings.GetString("ota_url")', source)
        self.assertIn(
            'SetHeader("Device-Id", SystemInfo::GetMacAddress().c_str())', source
        )
        self.assertIn('return url + "heartbeat"', source)

    def test_ota_and_heartbeat_reject_retired_product_routes(self):
        resolver = (ROOT / "main/ota_url.h").read_text(encoding="utf-8")
        ota = (ROOT / "main/ota.cc").read_text(encoding="utf-8")
        heartbeat = self.heartbeat_source()

        self.assertIn('"/xiaozhi/"', resolver)
        self.assertIn("CONFIG_OTA_URL", resolver)
        self.assertIn("ResolveOtaUrl", ota)
        self.assertIn("ResolveOtaUrl", heartbeat)

    def test_heartbeat_runs_every_sixty_seconds_and_skips_disconnected_wifi(self):
        source = self.heartbeat_source()

        self.assertIn("pdMS_TO_TICKS(60000)", source)
        self.assertIn("WifiManager::GetInstance().IsConnected()", source)

    def test_heartbeat_reads_the_success_response_before_closing(self):
        source = self.heartbeat_source()

        self.assertIn("status_code != 204", source)
        self.assertIn("http->ReadAll()", source)
        self.assertLess(source.index("http->ReadAll()"), source.index("http->Close()"))

    def test_application_starts_heartbeat_after_releasing_ota(self):
        source = (ROOT / "main/application.cc").read_text(encoding="utf-8")

        self.assertIn("device_heartbeat_.Start()", source)
        activation_done = source[source.index("void Application::HandleActivationDoneEvent()") :]
        self.assertLess(
            activation_done.index("ota_.reset()"),
            activation_done.index("device_heartbeat_.Start()"),
        )

    def test_heartbeat_is_built_as_an_independent_component(self):
        cmake = (ROOT / "main/CMakeLists.txt").read_text(encoding="utf-8")
        header = (ROOT / "main/application.h").read_text(encoding="utf-8")

        self.assertIn('"device_heartbeat.cc"', cmake)
        self.assertIn("DeviceHeartbeat device_heartbeat_;", header)


if __name__ == "__main__":
    unittest.main()
