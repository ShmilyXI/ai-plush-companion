#!/bin/bash
set -euo pipefail

# 固件快速编译与刷写脚本
# 用法: ./quick_flash.sh [板型名称] [--port /dev/cu.usbmodem101]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FIRMWARE_DIR="$PROJECT_ROOT"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

echo_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

echo_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查 ESP-IDF 环境
check_idf_env() {
    if ! command -v idf.py &> /dev/null; then
        echo_error "ESP-IDF 环境未配置"
        echo_info "请执行以下命令之一："
        echo "  1. 命令行安装: . ~/esp/esp-idf/export.sh"
        echo "  2. 或使用 VSCode/Cursor 的 ESP-IDF 插件"
        exit 1
    fi
    echo_info "ESP-IDF 环境: $(idf.py --version 2>&1 | head -1)"
}

# 列出可用板型
list_boards() {
    echo_info "可用板型："
    ls -1 "$FIRMWARE_DIR/main/boards" | grep -v common | nl
}

# 检测设备
detect_device() {
    local devices=($(ls /dev/cu.usb* 2>/dev/null || true))
    if [ ${#devices[@]} -eq 0 ]; then
        echo_error "未检测到 ESP32 设备"
        echo_info "请检查："
        echo "  1. 设备是否已连接"
        echo "  2. USB 驱动是否安装"
        exit 1
    fi

    if [ ${#devices[@]} -eq 1 ]; then
        echo_info "检测到设备: ${devices[0]}"
        echo "${devices[0]}"
    else
        echo_warn "检测到多个设备："
        for i in "${!devices[@]}"; do
            echo "  $((i+1)). ${devices[$i]}"
        done
        read -p "请选择设备编号 [1]: " choice
        choice=${choice:-1}
        echo "${devices[$((choice-1))]}"
    fi
}

# 获取本地 IP
get_local_ip() {
    ipconfig getifaddr en0 2>/dev/null || true
}

usage() {
    echo "用法: $0 [板型名称] [--port 串口] [--server-ip 地址] [--monitor]"
    echo "示例: $0 zhengchen-cam --port /dev/cu.usbmodem101 --server-ip 192.168.0.108"
}

read_ota_host() {
    local config_file="$1"
    sed -n 's/.*http:\\/\\/\\([^:"]*\\):.*/\\1/p' "$config_file" | head -1
}

build_assets() {
    local board_type="$1"
    local sdkconfig="$FIRMWARE_DIR/build/sdkconfig"
    local output="$FIRMWARE_DIR/build/generated_assets.bin"

    [ -f "$sdkconfig" ] || return 0
    grep -q '^CONFIG_USE_CUSTOM_WAKE_WORD=y$' "$sdkconfig" || return 0

    local wake_word
    wake_word=$(sed -n 's/^CONFIG_CUSTOM_WAKE_WORD_DISPLAY="\\(.*\\)"$/\\1/p' "$sdkconfig" | head -1)
    [ -n "$wake_word" ] || {
        echo_warn "已启用自定义唤醒词，但 sdkconfig 没有 CONFIG_CUSTOM_WAKE_WORD_DISPLAY，跳过 assets"
        return 0
    }

    echo_info "生成 assets 镜像: $output"
    python3 scripts/build_default_assets.py \
        --board "$board_type" \
        --sdkconfig "$sdkconfig" \
        --builtin_text_font font_puhui_basic_20_4 \
        --dynamic-wake-word-layout \
        --assets-partition-size 0x800000 \
        --default-wake-word "$wake_word" \
        --output "$output"
}

# 主函数
main() {
    cd "$FIRMWARE_DIR"

    local requested_port=""
    local expected_server_ip=""
    local monitor_now="N"
    local positional_board=""
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --port) requested_port="${2:?--port 需要串口路径}"; shift 2 ;;
            --server-ip) expected_server_ip="${2:?--server-ip 需要地址}"; shift 2 ;;
            --monitor) monitor_now="Y"; shift ;;
            -h|--help) usage; return 0 ;;
            -*) echo_error "未知参数: $1"; usage; return 2 ;;
            *) [ -z "$positional_board" ] || { echo_error "只能指定一个板型"; return 2; }; positional_board="$1"; shift ;;
        esac
    done

    echo_info "=== ESP32 固件编译与刷写工具 ==="
    echo ""

    # 1. 检查环境
    check_idf_env

    # 2. 选择板型
    BOARD_TYPE="$positional_board"
    if [ -z "$BOARD_TYPE" ]; then
        list_boards
        echo ""
        read -p "请输入板型名称 (例如: zhengchen-cam): " BOARD_TYPE
    fi

    if [ ! -d "$FIRMWARE_DIR/main/boards/$BOARD_TYPE" ]; then
        echo_error "板型 '$BOARD_TYPE' 不存在"
        list_boards
        exit 1
    fi

    echo_info "选择板型: $BOARD_TYPE"

    # 3. 检测设备
    if [ -n "$requested_port" ]; then
        [ -e "$requested_port" ] || { echo_error "串口不存在: $requested_port"; return 1; }
        DEVICE_PORT="$requested_port"
        echo_info "使用指定设备: $DEVICE_PORT"
    else
        DEVICE_PORT=$(detect_device | tail -1)
    fi
    export ESPPORT="$DEVICE_PORT"

    # 4. 获取服务器地址
    LOCAL_IP="${expected_server_ip:-$(get_local_ip)}"
    [ -n "$LOCAL_IP" ] && echo_info "期望的本地服务器: $LOCAL_IP" || echo_warn "未指定本地服务器地址"

    # 5. 检查配置
    CONFIG_FILE="$FIRMWARE_DIR/main/boards/$BOARD_TYPE/config.json"
    if grep -q "CONFIG_OTA_URL" "$CONFIG_FILE"; then
        current_ota=$(read_ota_host "$CONFIG_FILE")
        if [ -n "$LOCAL_IP" ] && [ -n "$current_ota" ] && [ "$current_ota" != "$LOCAL_IP" ]; then
            echo_error "板级配置的 OTA 地址是 $current_ota，但期望服务器是 $LOCAL_IP"
            echo_error "请先在 $CONFIG_FILE 中按部署环境修改并提交配置，再重新运行。脚本不会自动改写板级配置。"
            return 1
        fi
    fi

    # 6. 编译固件
    echo_info "开始编译..."
    export BOARD_TYPE

    # 清理旧编译
    read -p "是否清理旧编译? [y/N]: " clean_build
    if [[ "$clean_build" =~ ^[Yy]$ ]]; then
        echo_info "清理编译目录..."
        idf.py fullclean
    fi

    # 使用项目自己的 release.py 应用 config.json，并生成 merged-binary.bin
    echo_info "编译固件..."
    python3 scripts/release.py "$BOARD_TYPE" --name "$BOARD_TYPE" --force || {
        echo_error "编译失败"
        exit 1
    }

    # 编译 assets
    build_assets "$BOARD_TYPE"

    # 7. 刷写固件
    echo ""
    echo_info "固件编译完成！"
    echo_info "固件位置: build/merged-binary.bin"
    echo ""
    read -p "是否立即刷写到设备? [Y/n]: " flash_now
    flash_now=${flash_now:-Y}

    if [[ "$flash_now" =~ ^[Yy]$ ]]; then
        echo_info "刷写固件到 $DEVICE_PORT..."

        # 刷写
        echo_info "开始刷写..."
        idf.py -p "$DEVICE_PORT" flash

        # 刷写 assets（如果存在）
        if [ -f "build/generated_assets.bin" ]; then
            echo_info "刷写 assets..."
            esptool.py --chip esp32s3 --port "$DEVICE_PORT" --baud 460800 \
                write_flash 0x800000 build/generated_assets.bin
        fi

        echo ""
        echo_info "✅ 刷写完成！"
        echo ""

        # 询问是否监控
        if [[ "$monitor_now" =~ ^[Yy]$ ]]; then
            echo_info "启动串口监控（退出: Ctrl+]）..."
            sleep 2
            idf.py -p "$DEVICE_PORT" monitor
        fi
    else
        echo_info "手动刷写命令："
        echo "  idf.py -p $DEVICE_PORT flash"
        echo "  idf.py -p $DEVICE_PORT monitor"
    fi

    echo ""
    echo_info "=== 下一步 ==="
    echo "1. 设备配网: 连接设备的 Wi-Fi 热点，访问 http://192.168.4.1"
    echo "2. 管理控制台: http://localhost:8001"
    echo "3. 查看日志: idf.py -p $DEVICE_PORT monitor"
    echo ""
}

main "$@"
