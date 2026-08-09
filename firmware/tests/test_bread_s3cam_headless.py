import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class BreadS3CamHeadlessTest(unittest.TestCase):
    def board_source(self):
        return (
            ROOT
            / "main/boards/bread-compact-wifi-s3cam/compact_wifi_board_s3cam.cc"
        ).read_text(encoding="utf-8")

    def test_headless_build_option_skips_optional_hardware(self):
        kconfig = (ROOT / "main/Kconfig.projbuild").read_text(encoding="utf-8")
        board_source = self.board_source()

        self.assertIn("config BREAD_COMPACT_WIFI_CAM_HEADLESS", kconfig)
        self.assertIn("depends on BOARD_TYPE_BREAD_COMPACT_WIFI_CAM", kconfig)
        self.assertIn("#if !CONFIG_BREAD_COMPACT_WIFI_CAM_HEADLESS", board_source)
        self.assertIn("ESP_LOGI(TAG, \"Headless mode: LCD and camera disabled\")", board_source)
        self.assertIn("return Board::GetDisplay();", board_source)
        self.assertIn("return nullptr;", board_source)

    def test_normal_build_reports_display_and_camera_capabilities(self):
        board_source = self.board_source()

        self.assertIn("virtual std::string GetBoardJson() override", board_source)
        self.assertIn(
            'R"(,"has_display":true,"has_camera":true})"', board_source
        )

    def test_headless_build_reports_no_display_or_camera_capabilities(self):
        board_source = self.board_source()

        self.assertIn("virtual std::string GetBoardJson() override", board_source)
        self.assertIn(
            'R"(,"has_display":false,"has_camera":false})"', board_source
        )


if __name__ == "__main__":
    unittest.main()
