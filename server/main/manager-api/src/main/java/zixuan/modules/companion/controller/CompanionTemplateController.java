package zixuan.modules.companion.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.AllArgsConstructor;
import zixuan.common.utils.Result;
import zixuan.modules.agent.entity.AgentTemplateEntity;
import zixuan.modules.agent.service.AgentTemplateService;

@RestController
@AllArgsConstructor
@RequestMapping("/companion/templates")
public class CompanionTemplateController {
    private final AgentTemplateService templateService;

    @GetMapping
    @RequiresPermissions("sys:role:normal")
    public Result<List<CompanionTemplateVO>> list() {
        List<AgentTemplateEntity> templates = templateService.list(new QueryWrapper<AgentTemplateEntity>()
                .like("agent_code", "companion")
                .orderByAsc("sort"));
        return new Result<List<CompanionTemplateVO>>().ok(templates.stream().map(this::toVO).toList());
    }

    private CompanionTemplateVO toVO(AgentTemplateEntity template) {
        return new CompanionTemplateVO(template.getId(), template.getAgentCode(), template.getAgentName(),
                "friend");
    }

    public record CompanionTemplateVO(String id, String code, String name, String relationMode) {
    }
}
