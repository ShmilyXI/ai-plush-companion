package zixuan.modules.companion.capability.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

@Data
public class SkillPackageDraftDTO {
    private Map<String, Object> manifest = new LinkedHashMap<>();
    private String skillMarkdown;
    private Map<String, byte[]> assets = new LinkedHashMap<>();
}
