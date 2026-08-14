package xiaozhi.modules.model.tencentdb;

import cn.hutool.json.JSONObject;

public interface TencentDbMemoryModelSettingsService {
    TencentDbMemoryModelSettings requireEnabled();

    TencentDbMemoryModelSettings parseModelSettings(JSONObject config);

    TencentDbMemoryRuntimeSettings parseRuntime(JSONObject config);
}
