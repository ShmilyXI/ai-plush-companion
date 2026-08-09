#ifndef _DEVICE_HEARTBEAT_H
#define _DEVICE_HEARTBEAT_H

#include <string>

#include <esp_err.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

class DeviceHeartbeat {
public:
    DeviceHeartbeat() = default;
    ~DeviceHeartbeat();

    void Start();

private:
    TaskHandle_t task_handle_ = nullptr;

    static void TaskEntry(void* arg);
    void Run();
    esp_err_t Send();
    std::string GetHeartbeatUrl() const;
};

#endif
