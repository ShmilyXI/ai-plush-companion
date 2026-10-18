#include "wake_word_assets.h"

#include "audio/wake_words/custom_wake_word.h"
#include "board.h"
#include "settings.h"
#include "system_info.h"

#include <cJSON.h>
#include <esp_log.h>
#include <esp_system.h>
#include <mbedtls/sha256.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>

#define TAG "WakeWordAssets"

namespace {
struct WakeSlotHeader {
    char magic[4];
    uint32_t layout_version;
    uint64_t package_size;
    uint64_t version;
    uint8_t sha256[32];
};

struct MmapEntry {
    char name[32];
    uint32_t size;
    uint32_t offset;
    uint16_t width;
    uint16_t height;
};

bool HasLayoutHeader(const esp_partition_t* partition, size_t offset) {
    WakeSlotHeader header{};
    return esp_partition_read(partition, offset, &header, sizeof(header)) == ESP_OK
        && memcmp(header.magic, "XZWK", 4) == 0
        && header.layout_version == WakeWordAssets::kLayoutVersion;
}

std::string Hex(const uint8_t* bytes, size_t size) {
    static constexpr char digits[] = "0123456789abcdef";
    std::string value(size * 2, '0');
    for (size_t i = 0; i < size; ++i) {
        value[i * 2] = digits[bytes[i] >> 4];
        value[i * 2 + 1] = digits[bytes[i] & 0x0f];
    }
    return value;
}
}

WakeWordAssets& WakeWordAssets::GetInstance() {
    static WakeWordAssets instance;
    return instance;
}

WakeWordAssets::WakeWordAssets() {
    partition_ = esp_partition_find_first(
        ESP_PARTITION_TYPE_ANY, ESP_PARTITION_SUBTYPE_ANY, "assets");
    Settings settings("wake_word", false);
    int active_slot = settings.GetInt("active_slot", 0);
    if (MapSlot(active_slot) && RepairActiveSettings(active_slot)) return;

    UnmapSlot();
    int fallback_slot = active_slot == 0 ? 1 : 0;
    if (MapSlot(fallback_slot) && RepairActiveSettings(fallback_slot)) {
        ESP_LOGW(TAG, "Recovered wake word slot %d after slot %d became invalid",
                 fallback_slot, active_slot);
        return;
    }
    UnmapSlot();
}

WakeWordCapability WakeWordAssets::GetCapability() const {
#if CONFIG_IDF_TARGET_ESP32S3
    if (partition_ == nullptr) return {false, 0, 0, "assets partition missing"};
    if (partition_->size < 0x800000) return {false, 0, 0, "assets partition too small"};
    if (SystemInfo::GetChipModelName() != "esp32s3") {
        return {false, 0, 0, "unsupported chip: expected esp32s3"};
    }
    uint32_t base_length = 0;
    if (esp_partition_read(partition_, 8, &base_length, sizeof(base_length)) != ESP_OK
            || base_length > SlotOffset(0) - 12) {
        return {false, 0, 0, "base assets cross wake word slot A"};
    }
    if (!HasLayoutHeader(partition_, SlotOffset(0))
            && !HasLayoutHeader(partition_, SlotOffset(1))) {
        return {false, 0, 0, "wake word layout header is invalid"};
    }
    return {true, kLayoutVersion, kSlotSize, ""};
#else
    return {false, 0, 0, "unsupported chip: expected esp32s3"};
#endif
}

size_t WakeWordAssets::SlotOffset(int slot) const {
    return partition_->size - (kSlotCount - slot) * kSlotSize;
}

bool WakeWordAssets::HasPendingDownload() const {
    Settings settings("wake_word", false);
    return !settings.GetString("pending_url").empty();
}

void WakeWordAssets::SetPending(const std::string& url, const std::string& sha256,
                                size_t size, int64_t version, const std::string& word) {
    Settings settings("wake_word", true);
    settings.SetString("pending_url", url);
    settings.SetString("pending_sha", sha256);
    settings.SetInt("pending_size", static_cast<int32_t>(size));
    settings.SetInt("pending_ver", static_cast<int32_t>(version));
    settings.SetString("pending_word", word);
    settings.SetString("status", "pending");
    settings.EraseKey("error_code");
    settings.EraseKey("error_msg");
}

bool WakeWordAssets::DownloadPending(std::function<void(int, size_t)> progress_callback) {
    auto capability = GetCapability();
    if (!capability.supported) return Fail("UNSUPPORTED", capability.reason);
    Settings settings("wake_word", false);
    std::string url = settings.GetString("pending_url");
    std::string expected_sha = settings.GetString("pending_sha");
    size_t expected_size = static_cast<size_t>(settings.GetInt("pending_size", 0));
    int active_slot = settings.GetInt("active_slot", 0);
    int inactive_slot = active_slot == 0 ? 1 : 0;
    if (url.empty() || expected_sha.empty() || expected_size == 0
            || expected_size > kSlotSize - kHeaderSize) {
        return Fail("INVALID_PENDING", "pending metadata is invalid");
    }
    auto http = Board::GetInstance().GetNetwork()->CreateHttp(0);
    if (!http->Open("GET", url)) return Fail("HTTP_OPEN", "failed to open download");
    int status_code = http->GetStatusCode();
    if (status_code != 200) return Fail("HTTP_STATUS", "unexpected HTTP status");
    size_t content_length = http->GetBodyLength();
    if (content_length != expected_size) return Fail("CONTENT_LENGTH", "content length mismatch");

    size_t slot_offset = SlotOffset(inactive_slot);
    if (esp_partition_erase_range(partition_, slot_offset, kSlotSize) != ESP_OK) {
        return Fail("ERASE_FAILED", "failed to erase inactive slot");
    }
    std::array<uint8_t, 4096> buffer;
    mbedtls_sha256_context sha;
    mbedtls_sha256_init(&sha);
    mbedtls_sha256_starts(&sha, 0);
    size_t total_written = 0;
    while (total_written < content_length) {
        int read = http->Read(reinterpret_cast<char*>(buffer.data()),
                              std::min(buffer.size(), content_length - total_written));
        if (read <= 0) {
            mbedtls_sha256_free(&sha);
            return Fail("DOWNLOAD_FAILED", "download ended before content length");
        }
        mbedtls_sha256_update(&sha, buffer.data(), read);
        if (esp_partition_write(partition_, slot_offset + kHeaderSize + total_written,
                                buffer.data(), read) != ESP_OK) {
            mbedtls_sha256_free(&sha);
            return Fail("WRITE_FAILED", "failed to write inactive slot");
        }
        total_written += read;
        if (progress_callback) progress_callback(total_written * 100 / content_length, read);
    }
    uint8_t digest[32];
    mbedtls_sha256_finish(&sha, digest);
    mbedtls_sha256_free(&sha);
    if (Hex(digest, sizeof(digest)) != expected_sha) {
        return Fail("SHA256_MISMATCH", "download sha256 mismatch");
    }
    WakeSlotHeader header{};
    memcpy(header.magic, "XZWK", 4);
    header.layout_version = kLayoutVersion;
    header.package_size = content_length;
    header.version = settings.GetInt("pending_ver", 0);
    memcpy(header.sha256, digest, sizeof(digest));
    if (esp_partition_write(partition_, slot_offset, &header, sizeof(header)) != ESP_OK) {
        return Fail("HEADER_WRITE", "failed to commit candidate header");
    }
    candidate_slot_ = inactive_slot;
    return true;
}

bool WakeWordAssets::ActivateCandidate() {
    Settings settings("wake_word", false);
    previous_active_slot_ = settings.GetInt("active_slot", 0);
    previous_active_version_ = settings.GetInt("active_ver", 0);
    previous_active_word_ = settings.GetString("active_word");
    candidate_version_ = settings.GetInt("pending_ver", 0);
    candidate_word_ = settings.GetString("pending_word");
    if (candidate_slot_ < 0 || !MapSlot(candidate_slot_)) {
        return Fail("CANDIDATE_MAP", "failed to map candidate slot");
    }
    void* index_data = nullptr;
    size_t index_size = 0;
    void* model_data = nullptr;
    size_t model_size = 0;
    if (!GetAssetData("index.json", index_data, index_size)
            || !GetAssetData("srmodels.bin", model_data, model_size)) {
        return Fail("CANDIDATE_FILES", "index.json or srmodels.bin missing");
    }
    cJSON* index = cJSON_ParseWithLength(static_cast<char*>(index_data), index_size);
    std::string error_code;
    std::string error_message;
    cJSON* bundle = cJSON_GetObjectItem(index, "wake_word_bundle");
    cJSON* chip = cJSON_GetObjectItem(bundle, "chip");
    cJSON* version = cJSON_GetObjectItem(bundle, "version");
    cJSON* word = cJSON_GetObjectItem(bundle, "word");
    cJSON* multinet = cJSON_GetObjectItem(index, "multinet_model");
    cJSON* commands = cJSON_GetObjectItem(multinet, "commands");
    cJSON* command = cJSON_GetArrayItem(commands, 0);
    cJSON* action = cJSON_GetObjectItem(command, "action");
    bool metadata_valid = cJSON_IsString(chip) && strcmp(chip->valuestring, "esp32s3") == 0
        && cJSON_IsNumber(version) && version->valueint == settings.GetInt("pending_ver", 0)
        && cJSON_IsString(word) && settings.GetString("pending_word") == word->valuestring
        && cJSON_IsArray(commands) && cJSON_GetArraySize(commands) == 1
        && cJSON_IsString(action) && strcmp(action->valuestring, "wake") == 0;
    srmodel_list_t* models = srmodel_load(static_cast<uint8_t*>(model_data));
    bool valid = metadata_valid && models != nullptr && CustomWakeWord::ValidateConfiguration(
        models, index, &error_code, &error_message);
    if (models != nullptr) esp_srmodel_deinit(models);
    cJSON_Delete(index);
    if (!valid) {
        return Fail(error_code.empty() ? "METADATA_MISMATCH" : error_code,
                    error_message.empty() ? "wake word metadata does not match pending request" : error_message);
    }

    Settings writable("wake_word", true);
    writable.SetInt("active_slot", candidate_slot_);
    writable.SetInt("active_ver", settings.GetInt("pending_ver", 0));
    writable.SetString("active_word", settings.GetString("pending_word"));
    writable.SetString("status", "active");
    writable.EraseKey("pending_url");
    writable.EraseKey("pending_sha");
    writable.EraseKey("pending_size");
    writable.EraseKey("pending_ver");
    writable.EraseKey("pending_word");
    writable.EraseKey("error_code");
    writable.EraseKey("error_msg");
    return true;
}

bool WakeWordAssets::RollbackCandidate(const std::string& error_code,
                                       const std::string& error_message) {
    Settings settings("wake_word", false);
    int active_slot = previous_active_slot_ >= 0
        ? previous_active_slot_ : settings.GetInt("active_slot", 0);
    if (candidate_slot_ >= 0 && candidate_slot_ != active_slot) {
        esp_partition_erase_range(partition_, SlotOffset(candidate_slot_), kHeaderSize);
    }
    MapSlot(active_slot);
    Settings writable("wake_word", true);
    writable.SetInt("active_slot", active_slot);
    writable.SetInt("active_ver", previous_active_version_);
    writable.SetString("active_word", previous_active_word_);
    if (candidate_version_ > 0) writable.SetInt("pending_ver", candidate_version_);
    if (!candidate_word_.empty()) writable.SetString("pending_word", candidate_word_);
    candidate_slot_ = -1;
    previous_active_slot_ = -1;
    previous_active_version_ = 0;
    previous_active_word_.clear();
    candidate_version_ = 0;
    candidate_word_.clear();
    return Fail(error_code, error_message);
}

bool WakeWordAssets::Fail(const std::string& error_code, const std::string& error_message) {
    Settings settings("wake_word", true);
    settings.SetString("status", "failed");
    settings.SetString("error_code", error_code);
    settings.SetString("error_msg", error_message);
    return false;
}

void WakeWordAssets::UnmapSlot() {
    if (mmap_handle_ != 0) esp_partition_munmap(mmap_handle_);
    mmap_handle_ = 0;
    mmap_root_ = nullptr;
    assets_.clear();
}

bool WakeWordAssets::MapSlot(int slot) {
    if (partition_ == nullptr || slot < 0 || slot >= kSlotCount) return false;
    UnmapSlot();
    const void* address = nullptr;
    if (esp_partition_mmap(partition_, SlotOffset(slot), kSlotSize, ESP_PARTITION_MMAP_DATA,
                           &address, &mmap_handle_) != ESP_OK) return false;
    mmap_root_ = static_cast<const uint8_t*>(address);
    const auto* header = reinterpret_cast<const WakeSlotHeader*>(mmap_root_);
    if (memcmp(header->magic, "XZWK", 4) != 0 || header->layout_version != kLayoutVersion
            || header->package_size == 0 || header->package_size > kSlotSize - kHeaderSize) {
        UnmapSlot();
        return false;
    }
    uint8_t digest[32];
    mbedtls_sha256(mmap_root_ + kHeaderSize, header->package_size, digest, 0);
    if (memcmp(digest, header->sha256, sizeof(digest)) != 0 || !ParseMappedAssets()) {
        UnmapSlot();
        return false;
    }
    return true;
}

bool WakeWordAssets::RepairActiveSettings(int slot) {
    if (mmap_root_ == nullptr) return false;
    const auto* header = reinterpret_cast<const WakeSlotHeader*>(mmap_root_);
    if (header->version > static_cast<uint64_t>(std::numeric_limits<int32_t>::max())) {
        return false;
    }

    void* index_data = nullptr;
    size_t index_size = 0;
    void* model_data = nullptr;
    size_t model_size = 0;
    if (!GetAssetData("index.json", index_data, index_size)
            || !GetAssetData("srmodels.bin", model_data, model_size)) return false;
    cJSON* index = cJSON_ParseWithLength(static_cast<char*>(index_data), index_size);
    cJSON* bundle = cJSON_GetObjectItem(index, "wake_word_bundle");
    cJSON* bundle_version = cJSON_GetObjectItem(bundle, "version");
    cJSON* word = cJSON_GetObjectItem(bundle, "word");
    if (!cJSON_IsNumber(bundle_version)
            || bundle_version->valueint != static_cast<int32_t>(header->version)
            || !cJSON_IsString(word) || word->valuestring[0] == '\0') {
        cJSON_Delete(index);
        return false;
    }

    int32_t version = static_cast<int32_t>(header->version);
    std::string active_word = word->valuestring;
    Settings settings("wake_word", true);
    if (settings.GetInt("active_slot", -1) == slot
            && settings.GetInt("active_ver", -1) == version
            && settings.GetString("active_word") == active_word) {
        cJSON_Delete(index);
        return true;
    }

    srmodel_list_t* models = srmodel_load(static_cast<uint8_t*>(model_data));
    bool valid = models != nullptr
        && CustomWakeWord::ValidateConfiguration(models, index, nullptr, nullptr);
    if (models != nullptr) esp_srmodel_deinit(models);
    cJSON_Delete(index);
    if (!valid) return false;

    if (settings.GetInt("active_slot", -1) != slot) {
        settings.SetInt("active_slot", slot);
    }
    if (settings.GetInt("active_ver", -1) != version) {
        settings.SetInt("active_ver", version);
    }
    if (settings.GetString("active_word") != active_word) {
        settings.SetString("active_word", active_word);
    }
    return true;
}

bool WakeWordAssets::ParseMappedAssets() {
    const uint8_t* package = mmap_root_ + kHeaderSize;
    const auto* header = reinterpret_cast<const WakeSlotHeader*>(mmap_root_);
    if (header->package_size < 12) return false;
    uint32_t count = *reinterpret_cast<const uint32_t*>(package);
    uint32_t checksum = *reinterpret_cast<const uint32_t*>(package + 4);
    uint32_t length = *reinterpret_cast<const uint32_t*>(package + 8);
    if (length > header->package_size - 12 || count > 64
            || 12 + count * sizeof(MmapEntry) > header->package_size) return false;
    uint32_t calculated_checksum = 0;
    for (uint32_t i = 0; i < length; ++i) calculated_checksum += package[12 + i];
    if ((calculated_checksum & 0xffff) != checksum) return false;
    size_t payload_start = 12 + count * sizeof(MmapEntry);
    for (uint32_t i = 0; i < count; ++i) {
        const auto* entry = reinterpret_cast<const MmapEntry*>(package + 12 + i * sizeof(MmapEntry));
        size_t offset = payload_start + entry->offset;
        if (offset + 2 + entry->size > 12 + length
                || package[offset] != 'Z' || package[offset + 1] != 'Z') return false;
        assets_[std::string(entry->name, strnlen(entry->name, sizeof(entry->name)))] =
            {offset + 2, entry->size};
    }
    return true;
}

bool WakeWordAssets::GetAssetData(const std::string& name, void*& ptr, size_t& size) {
    auto found = assets_.find(name);
    if (found == assets_.end() || mmap_root_ == nullptr) return false;
    ptr = const_cast<uint8_t*>(mmap_root_ + kHeaderSize + found->second.first);
    size = found->second.second;
    return true;
}

std::string WakeWordAssets::GetStatusJson() const {
    Settings settings("wake_word", false);
    auto capability = GetCapability();
    cJSON* root = cJSON_CreateObject();
    cJSON_AddBoolToObject(root, "supported", capability.supported);
    cJSON_AddNumberToObject(root, "layout_version", capability.layout_version);
    cJSON_AddNumberToObject(root, "slot_size", capability.slot_size);
    cJSON_AddNumberToObject(root, "active_version", settings.GetInt("active_ver", 0));
    cJSON_AddStringToObject(root, "active_word", settings.GetString("active_word").c_str());
    cJSON_AddNumberToObject(root, "pending_version", settings.GetInt("pending_ver", 0));
    cJSON_AddStringToObject(root, "status", settings.GetString("status", "idle").c_str());
    cJSON_AddStringToObject(root, "error_code", settings.GetString("error_code").c_str());
    cJSON_AddStringToObject(root, "error_message", settings.GetString("error_msg").c_str());
    char* value = cJSON_PrintUnformatted(root);
    std::string result = value == nullptr ? "{}" : value;
    cJSON_free(value);
    cJSON_Delete(root);
    return result;
}
