package zixuan.modules.companion.controller;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.AllArgsConstructor;
import zixuan.common.utils.JsonUtils;
import zixuan.common.utils.Result;
import zixuan.modules.agent.entity.AgentTemplateEntity;
import zixuan.modules.agent.service.AgentTemplateService;

@RestController
@AllArgsConstructor
@RequestMapping("/companion/templates")
public class CompanionTemplateController {
    private static final List<String> CUES = List.of("laugh", "sigh", "hesitate", "breathe");
    private final AgentTemplateService templateService;

    @GetMapping
    @RequiresPermissions("sys:role:normal")
    public Result<List<CompanionTemplateVO>> list() {
        List<AgentTemplateEntity> templates = templateService.list(new QueryWrapper<AgentTemplateEntity>()
                .isNotNull("companion_cue_config")
                .orderByAsc("sort"));
        return new Result<List<CompanionTemplateVO>>().ok(templates.stream().map(this::toVO).toList());
    }

    private CompanionTemplateVO toVO(AgentTemplateEntity template) {
        List<String> cues = List.of();
        if (StringUtils.isNotBlank(template.getCompanionCueConfig())) {
            try {
                Map<String, Object> config = JsonUtils.parseMap(template.getCompanionCueConfig());
                cues = CUES.stream().filter(config::containsKey).toList();
            } catch (RuntimeException ignored) {
                cues = List.of();
            }
        }
        return new CompanionTemplateVO(template.getId(), template.getAgentCode(), template.getAgentName(),
                "friend", cues);
    }

    public record CompanionTemplateVO(String id, String code, String name, String relationMode, List<String> cues) {
    }
}
