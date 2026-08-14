package xiaozhi.modules.model.tencentdb;

import xiaozhi.modules.companion.model.vo.CompanionModelTestVO;

public interface TencentDbMemoryConnectionTester {
    CompanionModelTestVO test(TencentDbMemoryRuntimeSettings runtime);
}
