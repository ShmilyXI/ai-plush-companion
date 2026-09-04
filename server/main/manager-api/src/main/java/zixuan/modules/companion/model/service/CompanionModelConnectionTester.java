package zixuan.modules.companion.model.service;

import java.util.Map;

import zixuan.modules.companion.model.vo.CompanionModelTestVO;

public interface CompanionModelConnectionTester {
    CompanionModelTestVO test(String providerCode, Map<String, Object> runtimeConfig);
}
