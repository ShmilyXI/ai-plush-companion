package xiaozhi.modules.companion.capability.service;

import java.util.Map;

import xiaozhi.modules.companion.capability.vo.McpLocalConfigImportVO;

public interface McpLocalConfigImportService {
    McpLocalConfigImportVO importDocument(Long operatorId, Map<String, Object> document);
}
