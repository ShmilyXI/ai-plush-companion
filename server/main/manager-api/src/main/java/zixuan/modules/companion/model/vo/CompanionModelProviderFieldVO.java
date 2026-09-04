package zixuan.modules.companion.model.vo;

import java.util.List;

import lombok.Data;

@Data
public class CompanionModelProviderFieldVO {
    private String key;
    private String label;
    private String type;
    private boolean required;
    private boolean secret;
    private List<Object> options;
    private Object defaultValue;
}
