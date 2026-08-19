import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ZhengchenCameraCapabilityTest(unittest.TestCase):
    def test_camera_interface_exposes_readiness_and_esp32_camera_implements_it(self):
        interface = (ROOT / "main/boards/common/camera.h").read_text(encoding="utf-8")
        implementation = (ROOT / "main/boards/common/esp32_camera.h").read_text(
            encoding="utf-8"
        )

        self.assertIn("virtual bool IsReady() const", interface)
        self.assertIn("bool IsReady() const override", implementation)

    def test_zhengchen_board_reports_runtime_camera_capability(self):
        source = (ROOT / "main/boards/zhengchen-cam/zhengchen_cam_board.cc").read_text(
            encoding="utf-8"
        )

        self.assertIn("virtual std::string GetBoardJson() override", source)
        self.assertIn('"has_camera"', source)
        self.assertIn("camera_->IsReady()", source)

    def test_zhengchen_double_click_handles_unavailable_camera(self):
        source = (ROOT / "main/boards/zhengchen-cam/zhengchen_cam_board.cc").read_text(
            encoding="utf-8"
        )

        self.assertIn("if (camera == nullptr || !camera->Capture())", source)


if __name__ == "__main__":
    unittest.main()
