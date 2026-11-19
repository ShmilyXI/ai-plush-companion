package zixuan.modules.conversation.controller;

import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import zixuan.common.utils.Result;
import zixuan.modules.conversation.service.ConversationUsageService;

/**
 * 管理员查看对话用量。只有数量和耗时，没有对话内容。
 */
@AllArgsConstructor
@RestController
@RequestMapping("/admin/usage")
@Tag(name = "对话用量")
public class ConversationUsageController {
    private final ConversationUsageService usage;

    @GetMapping("/daily")
    @Operation(summary = "最近若干天每天的用量汇总")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<Map<String, Object>>> daily(@RequestParam(defaultValue = "14") int days) {
        return new Result<List<Map<String, Object>>>().ok(usage.dailySummary(days));
    }

    @GetMapping("/users")
    @Operation(summary = "某一天用得最多的用户")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<Map<String, Object>>> users(@RequestParam(required = false) String date,
            @RequestParam(defaultValue = "50") int limit) {
        return new Result<List<Map<String, Object>>>().ok(usage.topUsers(date, limit));
    }
}
