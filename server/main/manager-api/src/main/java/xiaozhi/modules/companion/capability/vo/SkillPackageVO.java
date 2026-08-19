package xiaozhi.modules.companion.capability.vo;

import java.util.Date;
import java.util.List;

import lombok.Data;

@Data
public class SkillPackageVO {
    private String id;
    private String capabilityId;
    private Integer version;
    private String packageSha256;
    private Long packageSize;
    private String source;
    private String validationStatus;
    private List<ValidationIssueVO> validationIssues = List.of();
    private Boolean published;
    private Date createdAt;
    private Date publishedAt;

    @Data
    public static class ValidationIssueVO {
        private String level;
        private String code;
        private String message;
    }
}
