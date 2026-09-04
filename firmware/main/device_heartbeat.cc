#include "device_heartbeat.h"

#include "board.h"
#include "ota_url.h"
#include "settings.h"
#include "system_info.h"

#include <esp_log.h>
#include <wifi_manager.h>

#define TAG "DeviceHeartbeat"

DeviceHeartbeat::~DeviceHeartbeat() {
    if (task_handle_ != nullptr) {
        vTaskDelete(task_handle_);
        task_handle_ = nullptr;
    }
}

void DeviceHeartbeat::Start() {
    if (task_handle_ != nullptr) {
        return;
    }
    if (xTaskCreate(TaskEntry, "device_heartbeat", 4096, this, 1, &task_handle_) != pdPASS) {
        task_handle_ = nullptr;
        ESP_LOGE(TAG, "Failed to start heartbeat task");
    }
}

void DeviceHeartbeat::TaskEntry(void* arg) {
    auto* heartbeat = static_cast<DeviceHeartbeat*>(arg);
    heartbeat->Run();
    heartbeat->task_handle_ = nullptr;
    vTaskDelete(nullptr);
}

void DeviceHeartbeat::Run() {
    while (true) {
        vTaskDelay(pdMS_TO_TICKS(60000));
        if (!WifiManager::GetInstance().IsConnected()) {
            continue;
        }
        esp_err_t err = Send();
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "Heartbeat failed, code=%d", err);
        }
    }
}

esp_err_t DeviceHeartbeat::Send() {
    auto& board = Board::GetInstance();
    auto http = board.GetNetwork()->CreateHttp(0);
    http->SetTimeout(10000);
    http->SetHeader("Device-Id", SystemInfo::GetMacAddress().c_str());
    http->SetHeader("Client-Id", board.GetUuid());
    Settings websocket_settings("websocket", false);
    std::string token = websocket_settings.GetString("token");
    if (!token.empty()) {
        if (token.find(' ') == std::string::npos) token = "Bearer " + token;
        http->SetHeader("Authorization", token.c_str());
    }
    http->SetHeader("User-Agent", SystemInfo::GetUserAgent());
    http->SetHeader("Content-Type", "application/json");
    http->SetContent(board.GetSystemInfoJson());

    std::string url = GetHeartbeatUrl();
    if (!http->Open("POST", url)) {
        return http->GetLastError();
    }
    int status_code = http->GetStatusCode();
    http->ReadAll();
    http->Close();
    if (status_code != 204) {
        ESP_LOGW(TAG, "Heartbeat returned HTTP %d", status_code);
        return ESP_FAIL;
    }
    return ESP_OK;
}

std::string DeviceHeartbeat::GetHeartbeatUrl() const {
    Settings settings("wifi", false);
    std::string url = ResolveOtaUrl(settings.GetString("ota_url"));
    size_t suffix = url.find_first_of("?#");
    if (suffix != std::string::npos) {
        url.erase(suffix);
    }
    if (url.empty() || url.back() != '/') {
        url.push_back('/');
    }
    return url + "heartbeat";
}
