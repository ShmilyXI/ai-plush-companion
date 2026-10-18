package zixuan.modules.companion.capability.vo;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class SkillPackageValidationVO {
    private String status;
    private List<Issue> issues = new ArrayList<>();

    public List<String> errorCodes() {
        return issues.stream().filter(issue -> "ERROR".equals(issue.getLevel())).map(Issue::getCode).toList();
    }

    @Data
    public static class Issue {
        private String level;
        private String code;
        private String message;

        public Issue() {
        }

        public Issue(String level, String code, String message) {
            this.level = level;
            this.code = code;
            this.message = message;
        }
    }
}
