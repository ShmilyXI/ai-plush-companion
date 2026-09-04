#ifndef OTA_URL_H
#define OTA_URL_H

#include <string>

inline bool IsRetiredProductOtaUrl(const std::string& url) {
    return url.find("/xiaozhi/") != std::string::npos;
}

inline std::string ResolveOtaUrl(const std::string& configured_url) {
    if (configured_url.empty() || IsRetiredProductOtaUrl(configured_url)) {
        return CONFIG_OTA_URL;
    }
    return configured_url;
}

#endif  // OTA_URL_H
