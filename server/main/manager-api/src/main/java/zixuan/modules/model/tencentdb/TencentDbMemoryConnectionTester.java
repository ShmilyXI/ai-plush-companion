package zixuan.modules.model.tencentdb;

import zixuan.modules.companion.model.vo.CompanionModelTestVO;

public interface TencentDbMemoryConnectionTester {
    CompanionModelTestVO test(TencentDbMemoryRuntimeSettings runtime);
}
