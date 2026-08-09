package xiaozhi.modules.companion.model.vo;

import java.util.List;

import lombok.Data;

@Data
public class CompanionModelProviderTemplateVO {
    private String id;
    private String modelType;
    private String providerCode;
    private String name;
    private Integer sort;
    private List<CompanionModelProviderFieldVO> fields;
}
