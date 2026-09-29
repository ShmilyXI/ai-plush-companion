# Fluent Emoji 3D 表情集

本目录是 [microsoft/fluentui-emoji](https://github.com/microsoft/fluentui-emoji) 的 **3D 风格**子集，
按固件 `EmojiCollection` 的 21 个标准情绪名重命名，供 `firmware/scripts/build_default_assets.py` 打包进 assets 分区。

- 授权:fluentui-emoji 采用 **MIT License**,可商用,无需付费。上游仓库要求保留版权与许可声明,详见
  [上游 LICENSE](https://github.com/microsoft/fluentui-emoji/blob/main/LICENSE)。
- 尺寸:176x176 RGBA PNG(Fluent 原图为 256x256,为适配 8 MiB assets 分区中 2 MiB 基础区的空间预算统一缩小)。
- 命名:与固件 `EmojiCollection` 的 21 个情绪名一一对应,无需别名。

## 情绪到上游文件的映射

| 情绪名 | 上游资源 |
|---|---|
| neutral | Face without mouth (U+1F636) |
| happy | Slightly smiling face (U+1F642) |
| laughing | Grinning squinting face (U+1F606) |
| funny | Face with tears of joy (U+1F602) |
| sad | Pensive face (U+1F614) |
| angry | Angry face (U+1F620) |
| crying | Loudly crying face (U+1F62D) |
| loving | Smiling face with heart-eyes (U+1F60D) |
| embarrassed | Flushed face (U+1F633) |
| surprised | Face with open mouth (U+1F62F) |
| shocked | Face screaming in fear (U+1F631) |
| thinking | Thinking face (U+1F914) |
| winking | Winking face (U+1F609) |
| cool | Smiling face with sunglasses (U+1F60E) |
| relaxed | Relieved face (U+1F60C) |
| delicious | Drooling face (U+1F924) |
| kissy | Face blowing a kiss (U+1F618) |
| confident | Smirking face (U+1F60F) |
| sleepy | Sleeping face (U+1F634) |
| silly | Winking face with tongue (U+1F61C) |
| confused | Face with rolling eyes (U+1F644) |

## 重新生成

```bash
# 下载上游 3D PNG(路径形如 assets/<Name>/3D/<name>_3d.png)后统一缩放:
for f in *.png; do ffmpeg -y -i "$f" -vf "scale=176:176:flags=lanczos" -compression_level 9 "out/$f"; done
```

替换或新增情绪图时保持文件名与情绪名一致(小写、下划线),并注意 2 MiB 基础区总预算。
