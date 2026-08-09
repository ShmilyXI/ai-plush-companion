package xiaozhi.modules.companion.model.vo;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CompanionRuntimeModel {
    private String id;
    private Map<String, Object> config;
}
