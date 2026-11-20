package zixuan.modules.analytics.controller;

import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import zixuan.common.utils.Result;
import zixuan.modules.analytics.dto.ClientEventsDTO;
import zixuan.modules.analytics.service.AnalyticsService;
import zixuan.modules.security.user.SecurityUser;

@AllArgsConstructor
@RestController
@Tag(name = "产品数据")
public class AnalyticsController {
    private final AnalyticsService analytics;

    @PostMapping("/app/v1/events")
    @Operation(summary = "客户端上报事件")
    public Result<Map<String, Integer>> events(@RequestBody(required = false) ClientEventsDTO dto) {
        return new Result<Map<String, Integer>>().ok(
                Map.of("recorded", analytics.recordEvents(SecurityUser.getUserId(), dto)));
    }

    @GetMapping("/admin/analytics/overview")
    @Operation(summary = "留存、延迟和客户端事件总览")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Map<String, Object>> overview(@RequestParam(defaultValue = "30") int days) {
        return new Result<Map<String, Object>>().ok(analytics.overview(days));
    }
}
