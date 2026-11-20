package zixuan.modules.conversation.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import zixuan.common.page.PageData;
import zixuan.common.utils.Result;
import zixuan.modules.conversation.entity.SafetyEventEntity;
import zixuan.modules.conversation.service.ConversationSafetyService;

/**
 * 管理员复核内容安全事件。事件里只有类别和处理方式，没有对话内容。
 */
@AllArgsConstructor
@RestController
@RequestMapping("/admin/safety-events")
@Tag(name = "内容安全事件")
public class ConversationSafetyController {
    private final ConversationSafetyService safety;

    @GetMapping
    @Operation(summary = "安全事件列表，最新的在前")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<SafetyEventEntity>> page(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long limit) {
        return new Result<PageData<SafetyEventEntity>>().ok(safety.page(page, limit));
    }
}
