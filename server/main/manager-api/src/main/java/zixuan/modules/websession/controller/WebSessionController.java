package zixuan.modules.websession.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import zixuan.common.utils.Result;
import zixuan.modules.security.user.SecurityUser;
import zixuan.modules.websession.dto.WebSessionExchangeDTO;
import zixuan.modules.websession.dto.WebSessionBootstrapDTO;
import zixuan.modules.websession.service.WebSessionBootstrapService;

@RestController
@RequestMapping("/api/v1/web-sessions")
@Tag(name = "Web 会话", description = "管理台到独立 Web 应用的一次性登录交换")
public class WebSessionController {
    private final WebSessionBootstrapService service;

    public WebSessionController(WebSessionBootstrapService service) {
        this.service = service;
    }

    @PostMapping("/bootstrap")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "创建 Web 应用一次性启动码")
    public Result<WebSessionBootstrapService.Bootstrap> bootstrap(
            @RequestBody @Valid WebSessionBootstrapDTO request) {
        return new Result<WebSessionBootstrapService.Bootstrap>()
                .ok(service.issue(SecurityUser.getUserId(), request.getAudience(), request.getOrigin()));
    }

    @PostMapping("/exchange")
    @Operation(summary = "交换 Web 应用短期凭据")
    public Result<WebSessionBootstrapService.Exchange> exchange(
            @RequestBody @Valid WebSessionExchangeDTO request) {
        return new Result<WebSessionBootstrapService.Exchange>().ok(service.exchange(request.getCode()));
    }
}
