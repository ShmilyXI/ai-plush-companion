package xiaozhi.modules.companion.capability.vo;

import java.util.List;

import lombok.Data;

@Data
public class McpLocalConfigImportVO {
    private List<String> imported = List.of();
    private List<String> skipped = List.of();
}
