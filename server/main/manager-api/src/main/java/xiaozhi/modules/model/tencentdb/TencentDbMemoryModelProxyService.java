package xiaozhi.modules.model.tencentdb;

import cn.hutool.json.JSONObject;

public interface TencentDbMemoryModelProxyService {
    TencentDbMemoryProxyResponse chat(JSONObject request);

    TencentDbMemoryProxyResponse embeddings(JSONObject request);
}
