#ifndef WAKE_WORD_ASSETS_H
#define WAKE_WORD_ASSETS_H

#include <functional>
#include <map>
#include <string>

#include <esp_partition.h>
#include <spi_flash_mmap.h>

struct WakeWordCapability {
    bool supported;
    int layout_version;
    size_t slot_size;
    std::string reason;
};

class WakeWordAssets {
public:
    static constexpr int kSlotCount = 2;
    static constexpr int kLayoutVersion = 2;
    static constexpr size_t kSlotSize = 0x300000;
    static constexpr size_t kHeaderSize = 0x1000;

    static WakeWordAssets& GetInstance();
    WakeWordCapability GetCapability() const;
    bool HasPendingDownload() const;
    bool DownloadPending(std::function<void(int, size_t)> progress_callback);
    bool ActivateCandidate();
    bool RollbackCandidate(const std::string& error_code, const std::string& error_message);
    bool GetAssetData(const std::string& name, void*& ptr, size_t& size);
    std::string GetStatusJson() const;
    void SetPending(const std::string& url, const std::string& sha256,
                    size_t size, int64_t version, const std::string& word);

private:
    WakeWordAssets();
    bool MapSlot(int slot);
    bool RepairActiveSettings(int slot);
    void UnmapSlot();
    bool ParseMappedAssets();
    size_t SlotOffset(int slot) const;
    bool Fail(const std::string& error_code, const std::string& error_message);

    const esp_partition_t* partition_ = nullptr;
    esp_partition_mmap_handle_t mmap_handle_ = 0;
    const uint8_t* mmap_root_ = nullptr;
    std::map<std::string, std::pair<size_t, size_t>> assets_;
    int candidate_slot_ = -1;
    int previous_active_slot_ = -1;
    int32_t previous_active_version_ = 0;
    std::string previous_active_word_;
    int32_t candidate_version_ = 0;
    std::string candidate_word_;
};

#endif
