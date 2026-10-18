package zixuan.modules.conversation.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import zixuan.common.utils.Result;
import zixuan.modules.conversation.dto.PublicConversationCreateDTO;
import zixuan.modules.conversation.service.PublicConversationService;
import zixuan.modules.conversation.vo.PublicConversationSessionVO;
import zixuan.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "公共对话", description = "与设备协议隔离的文字和音频对话会话")
@SecurityRequirement(name = "publicApiKey")
public class PublicConversationController {
    private final PublicConversationService service;

    public PublicConversationController(PublicConversationService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "创建公共对话会话", description = "支持第一方 Bearer token 或带 scope 的 ApiKey")
    public Result<PublicConversationSessionVO> create(@RequestBody @Valid PublicConversationCreateDTO request) {
        return new Result<PublicConversationSessionVO>().ok(service.create(SecurityUser.getUserId(), request));
    }
}
