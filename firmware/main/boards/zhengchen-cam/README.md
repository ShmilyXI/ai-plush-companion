# 产品相关介绍网址

## 简介
征辰科技 AI camera 是紫萱的摄像头板级实现，包含针对该硬件的功能与性能优化。

## 合并版
该板型随紫萱主项目一起维护，支持语音唤醒、语音打断和 OTA 等功能。

## 魔改版
魔改版由于底层改动太大，代码单独维护，定期合并主项目代码。

https://e.tb.cn/h.6Gl2LC7rsrswQZp?tk=qFuaV9hzh0k CZ356
```
原厂商品名：【淘宝】「小智AI带摄像头支持识物双麦克风打断 ESP32S3N16R8开发板表情包」
https://e.tb.cn/h.hBc8Gcx9cUluJJO?tk=YW5C4LPixKg



## 配置、编译命令

由于此项目需要配置较多的 sdkconfig 选项，推荐使用编译脚本编译。

**编译**

```bash
python ./scripts/release.py zhengchen-cam
```

如需手动编译，请参考 `zhengchen-cam/config.json` 修改 menuconfig 对应选项。

**烧录**

```bash
idf.py flash


```

**生成 assets 镜像（含表情集与动态唤醒词槽位）**

板级 `config.json` 已声明 `assets.default_emoji_collection = "fluent_3d"`（Fluent Emoji 3D，MIT 授权，
位于 `firmware/resources/emoji/fluent_3d/`），生成镜像时会自动打包：

```bash
# 在 firmware/ 目录下执行；--sdkconfig 使用构建目录生成的板级 sdkconfig
python ./scripts/build_default_assets.py \
    --board zhengchen-cam \
    --sdkconfig <build_dir>/sdkconfig \
    --builtin_text_font font_puhui_basic_20_4 \
    --dynamic-wake-word-layout \
    --assets-partition-size 0x800000 \
    --default-wake-word "你好紫萱" \
    --output generated_assets.bin
```

已绑定设备只刷 `generated_assets.bin`（偏移 `0x800000`），保留 NVS；不要整片刷写。
表情集变更后需重新生成并刷写该镜像才会生效。

MCP Tool：
self.get_device_status
self.audio_speaker.set_volume
self.screen.set_brightness
self.screen.set_theme
self.gif.set_gif_mode
self.display.set_mode
self.camera.take_photo       
self.AEC.set_mode
self.AEC.get_mode
self.res.esp_restart
