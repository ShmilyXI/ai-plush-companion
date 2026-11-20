package zixuan.modules.model.service;

import java.util.List;

/**
 * 向服务商查询可用模型。请求只从管理服务发出，密钥不经过浏览器。
 */
public interface ModelChannelUpstreamClient {

    /**
     * @return 服务商给出的模型代号，已去重并排序
     * @throws zixuan.common.exception.RenException 请求失败时抛出，消息可以直接展示，不包含密钥
     */
    List<String> listModels(String providerCode, String baseUrl, String apiKey);
}
