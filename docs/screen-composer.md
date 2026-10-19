# 屏幕编排与图片资源

ESP-VoCat 的屏幕是 360×360 逻辑像素的圆形 ST77916 QSPI 屏，触摸坐标与画布坐标一致。上传图片建议使用 360×360 PNG 或 JPEG，重要内容四周保留 24 像素安全边距，设备会缩放到 RGB565 并保持 360×360。

管理台路径是 `/screen-composer`。输入设备 ID 后可以保存场景草稿并发布版本。图片上传会生成 RGB565 资源地址，发布到在线设备时，设备通过 `self.screen.set_image_url` 下载并显示。

设备支持五种模式：`static_image`、`chat`、`emotion_chat`、`text_chat` 和 `custom`。长按屏幕 800ms 会循环切换模式。设备 MCP 的 `self.screen.get_info` 会返回当前宽高、形状、触摸支持和模式列表。

资源下载失败时设备保留当前显示内容。设备只接受管理端生成的 RGB565 资源，不接受脚本或任意可执行文件。

