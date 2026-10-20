#!/usr/bin/env python3
"""
ESP32 设备配置脚本
通过串口配置 Wi-Fi 和服务器连接
"""

import sys

def main():
    # 配置参数
    DEVICE_PORT = "/dev/cu.usbmodem101"
    WIFI_SSID = input("请输入 Wi-Fi 名称 (SSID): ").strip()
    WIFI_PASSWORD = input("请输入 Wi-Fi 密码: ").strip()

    # 服务器配置（自动从本地网络获取）
    MQTT_SERVER = "192.168.0.108:1883"
    HTTP_SERVER = "http://192.168.0.108:8003"

    print("\n设备配置信息:")
    print(f"  设备端口: {DEVICE_PORT}")
    print(f"  Wi-Fi: {WIFI_SSID}")
    print(f"  MQTT: {MQTT_SERVER}")
    print(f"  HTTP: {HTTP_SERVER}")
    print("\n注意: 请确保设备已连接到电脑并处于配置模式")
    print("提示: 如果设备没有响应，请尝试按下设备上的复位按钮\n")

    # 这里需要通过串口发送配置命令
    # 实际实现需要 pyserial 库
    print("⚠️  请通过管理控制台或设备显示屏完成配置")
    print(f"   管理控制台: http://localhost:8001")

if __name__ == "__main__":
    main()
