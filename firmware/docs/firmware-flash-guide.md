# 固件编译与刷写指南

## 问题诊断

你的设备当前运行的是原版小智（xiaozhi.me）的固件，内置的服务器地址指向官方服务器：
- 官方 MQTT: mqtt.xiaozhi.me
- 官方 HTTP: api.xiaozhi.me

要连接到本地服务器（192.168.0.108），必须重新编译并刷写固件，使其内置本地服务器地址。

## 前置要求

### 硬件
- ESP32-S3 设备（当前连接: `/dev/cu.usbmodem101`）
- USB 数据线
- 根据数据库记录，你的设备可能是：
  - zhengchen-cam (正晨摄像头版)
  - bread-compact-wifi-s3cam (面包板 WiFi 摄像头版)

### 软件
- macOS（已确认）
- Python 3
- ESP-IDF 5.5.2 或更高版本
- esptool

## 方案选择

### 方案 A: 使用预编译固件（推荐，快速）

如果项目维护者提供了预编译固件，可以直接下载并刷写。

### 方案 B: 本地编译固件（完整控制）

从源码编译，可以自定义所有配置。

---

## 方案 A: 使用预编译固件

### 1. 安装 esptool

```bash
pip3 install esptool
```

### 2. 下载固件

检查是否有预编译固件：
```bash
ls firmware/releases/
```

如果没有，需要使用方案 B 自行编译。

### 3. 刷写固件

```bash
# 已绑定设备不要擦除整个 flash。NVS 保存 Wi-Fi、设备 UUID、token 和绑定关系。
# 只刷项目生成的各个分区，保留 NVS。
idf.py -p /dev/cu.usbmodem101 flash
```

---

## 方案 B: 本地编译固件

### 步骤 1: 安装 ESP-IDF

```bash
# 1. 创建 ESP-IDF 目录
mkdir -p ~/esp
cd ~/esp

# 2. 克隆 ESP-IDF（需要 5.5.2 或更高版本）
git clone -b v5.5.2 --recursive https://github.com/espressif/esp-idf.git

# 3. 安装 ESP-IDF
cd esp-idf
./install.sh esp32,esp32s3,esp32c3,esp32p4

# 4. 配置环境变量（每次新终端都需要执行）
. ~/esp/esp-idf/export.sh
```

或者使用 VSCode/Cursor 的 ESP-IDF 插件（推荐）：
1. 安装 "ESP-IDF" 扩展
2. 按 Cmd+Shift+P，运行 "ESP-IDF: Configure ESP-IDF Extension"
3. 选择 ESP-IDF 版本 5.5.2
4. 等待安装完成

### 步骤 2: 配置固件

#### 2.1 确定板型

根据数据库记录，你的设备可能是：
- `zhengchen-cam` - 如果有摄像头和较大屏幕
- `bread-compact-wifi-s3cam` - 如果是面包板制作

检查设备外观确定板型。

#### 2.2 修改板型配置

编辑对应板型的配置文件（以 zhengchen-cam 为例）：

```bash
# 编辑配置
vi firmware/main/boards/zhengchen-cam/config.json
```

关键配置项：
```json
{
    "target": "esp32s3",
    "builds": [
        {
            "name": "zhengchen-cam",
            "sdkconfig_append": [
                "CONFIG_OTA_URL=\"http://192.168.0.108:8002/zixuan/ota/\"",
                "CONFIG_ESPTOOLPY_FLASHSIZE_16MB=y",
                "CONFIG_PARTITION_TABLE_CUSTOM_FILENAME=\"partitions/v2/16m.csv\"",
                "CONFIG_USE_DEVICE_AEC=y",
                "CONFIG_USE_CUSTOM_WAKE_WORD=y",
                "CONFIG_CUSTOM_WAKE_WORD=\"ni hao zi xuan\"",
                "CONFIG_CUSTOM_WAKE_WORD_DISPLAY=\"你好紫萱\"",
                "CONFIG_CUSTOM_WAKE_WORD_THRESHOLD=20"
            ]
        }
    ]
}
```

**重要**：将 `CONFIG_OTA_URL` 改为你的本地服务器地址：
```
CONFIG_OTA_URL="http://192.168.0.108:8002/zixuan/ota/"
```

### 步骤 3: 编译固件

```bash
cd firmware

# 激活 ESP-IDF 环境（如果使用命令行安装）
. ~/esp/esp-idf/export.sh

# 使用项目发布脚本编译目标板
python3 scripts/release.py zhengchen-cam --name zhengchen-cam --force
python3 scripts/build_default_assets.py \
  --board zhengchen-cam \
  --sdkconfig build/sdkconfig \
  --builtin_text_font font_puhui_basic_20_4 \
  --dynamic-wake-word-layout \
  --assets-partition-size 0x800000 \
  --default-wake-word 你好紫萱 \
  --output build/generated_assets.bin

```

编译成功后，固件位于：
- `build/merged-binary.bin` - 完整固件（包含 bootloader、分区表、应用）
- `build/zixuan.bin` - 仅应用固件（OTA 用）
- `build/generated_assets.bin` - 资源镜像

### 步骤 4: 刷写固件

#### 4.1 全新刷写（推荐）

```bash
# 方法 1: 使用 idf.py（推荐）
cd firmware
idf.py -p /dev/cu.usbmodem101 flash

# 方法 2: 手动刷写
esptool.py --chip esp32s3 \
  --port /dev/cu.usbmodem101 \
  --baud 460800 \
  write_flash -z 0x0 build/merged-binary.bin

# 刷写 assets 镜像
esptool.py --chip esp32s3 \
  --port /dev/cu.usbmodem101 \
  --baud 460800 \
  write_flash 0x800000 build/generated_assets.bin
```

#### 4.2 仅更新应用（如果之前已刷写过）

```bash
esptool.py --chip esp32s3 \
  --port /dev/cu.usbmodem101 \
  --baud 460800 \
  write_flash 0x10000 build/zixuan.bin
```

### 步骤 5: 查看启动日志

```bash
# 方法 1: 使用 idf.py
idf.py -p /dev/cu.usbmodem101 monitor

# 方法 2: 使用 screen
screen /dev/cu.usbmodem101 115200

# 退出 screen: Ctrl-A 然后按 K，确认退出
```

应该看到类似输出：
```
I (xxx) main: Zixuan v2.2.7
I (xxx) main: Board: zhengchen-cam/2.2.7
I (xxx) SystemInfo: free sram: xxxxx minimal sram: xxxxx
I (xxx) wifi: connecting to WiFi...
```

---

## 配置设备连接

刷写完成后，设备会自动进入配网模式：

### 方法 1: Wi-Fi 配网（推荐）

1. 设备会创建一个 Wi-Fi 热点，名称类似：
   - `ESP32-xxxxxx`
   - `Zixuan-xxxxxx`
   - 或板型名称

2. 手机连接该热点

3. 浏览器访问 `http://192.168.4.1`

4. 选择你的 Wi-Fi 网络并输入密码

5. 设备会自动连接并重启

### 方法 2: 串口配置（高级）

通过串口输入配置命令（具体命令取决于固件版本）。

---

## 验证连接

### 1. 查看串口日志

应该看到：
```
I (xxx) wifi: connected to WiFi: YourSSID
I (xxx) mqtt: connecting to 192.168.0.108:1883...
I (xxx) mqtt: connected
I (xxx) device: registered with server
```

### 2. 查看管理控制台

打开 http://localhost:8001
- 进入「设备管理」
- 应该看到你的设备在线

### 3. 查看数据库

```bash
docker exec -e MYSQL_PWD=123456 ai-plush-companion-mysql \
  mysql -uroot -N -B -e \
  "SELECT id, board, alias, last_connected_at FROM xiaozhi_esp32_server.ai_device ORDER BY last_connected_at DESC LIMIT 5;"
```

应该看到设备的最新连接时间。

---

## 故障排查

### 编译错误

**错误**: `idf.py: command not found`
- 解决: 执行 `. ~/esp/esp-idf/export.sh`

**错误**: `CMake Error: Could not find toolchain file`
- 解决: 运行 `idf.py set-target esp32s3`

**错误**: `No such file or directory: 'xtensa-esp32s3-elf-gcc'`
- 解决: 重新安装 ESP-IDF 工具链

### 刷写错误

**错误**: `Failed to connect to ESP32`
- 解决1: 按住设备的 BOOT 按钮，然后插入 USB
- 解决2: 检查 USB 驱动
- 解决3: 尝试更低的波特率（115200）

**错误**: `A fatal error occurred: MD5 of file does not match`
- 解决: 重新编译固件

### 设备无法连接服务器

**检查项**:
1. 设备和服务器在同一局域网？
2. 服务器地址配置正确？（192.168.0.108）
3. MQTT 网关正在运行？（端口 1883）
4. 防火墙是否阻止连接？

**调试命令**:
```bash
# 检查 MQTT 网关
lsof -i :1883

# 检查服务器服务
docker ps

# 测试 MQTT 连接
telnet 192.168.0.108 1883
```

---

## 后续步骤

固件刷写并连接成功后：

1. **测试语音功能**
   - 说出唤醒词 "你好紫萱"
   - 进行对话测试

2. **配置设备能力**
   - 在管理控制台绑定 Skill
   - 配置设备工具

3. **查看设备日志**
   - 通过串口监控实时日志
   - 在管理控制台查看设备状态

4. **OTA 更新**
   - 后续可以通过 OTA 更新固件
   - 无需再次通过 USB 刷写

---

## 参考资料

- [固件 README](../firmware/README_zh.md)
- [自定义开发板指南](../firmware/docs/custom-board_zh.md)
- [ESP-IDF 官方文档](https://docs.espressif.com/projects/esp-idf/zh_CN/latest/)
- [AGENTS.md - 项目开发约定](../AGENTS.md)
