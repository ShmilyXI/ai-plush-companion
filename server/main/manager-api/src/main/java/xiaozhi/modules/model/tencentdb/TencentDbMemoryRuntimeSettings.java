package xiaozhi.modules.model.tencentdb;

import java.net.URI;

public record TencentDbMemoryRuntimeSettings(
        URI memoryCoreUrl,
        String memoryCoreApiKey,
        TencentDbMemoryModelSettings modelSettings) {
}
