package xiaozhi.modules.conversation.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.dto.PublicConversationApiKeyCreateDTO;
import xiaozhi.modules.conversation.service.PublicConversationApiKeyService;
import xiaozhi.modules.conversation.vo.PublicConversationApiKeyVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "公共对话 API Key", description = "创建、查看和撤销第三方公共对话调用凭据")
@SecurityRequirement(name = "bearerAuth")
public class PublicConversationApiKeyController {
    private final PublicConversationApiKeyService service;

    public PublicConversationApiKeyController(PublicConversationApiKeyService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "创建公共对话 API Key", description = "原始 Key 只在本次响应的 createdSecret 字段返回一次")
    public Result<PublicConversationApiKeyVO> create(@RequestBody @Valid PublicConversationApiKeyCreateDTO request) {
        return new Result<PublicConversationApiKeyVO>().ok(service.create(SecurityUser.getUserId(), request));
    }

    @GetMapping
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "列出当前用户的公共对话 API Key")
    public Result<List<PublicConversationApiKeyVO>> list() {
        return new Result<List<PublicConversationApiKeyVO>>().ok(service.list(SecurityUser.getUserId()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "撤销公共对话 API Key")
    public Result<Void> revoke(@PathVariable String id) {
        service.revoke(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }
}
