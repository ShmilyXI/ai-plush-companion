package zixuan.modules.companion.capability.service;

import java.util.Map;

import zixuan.modules.companion.capability.vo.McpLocalConfigImportVO;

public interface McpLocalConfigImportService {
    McpLocalConfigImportVO importDocument(Long operatorId, Map<String, Object> document);
}
