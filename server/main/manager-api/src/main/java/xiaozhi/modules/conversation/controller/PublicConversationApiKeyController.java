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
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.dto.PublicConversationApiKeyCreateDTO;
import xiaozhi.modules.conversation.service.PublicConversationApiKeyService;
import xiaozhi.modules.conversation.vo.PublicConversationApiKeyVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/api/v1/api-keys")
public class PublicConversationApiKeyController {
    private final PublicConversationApiKeyService service;

    public PublicConversationApiKeyController(PublicConversationApiKeyService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermissions("sys:role:normal")
    public Result<PublicConversationApiKeyVO> create(@RequestBody @Valid PublicConversationApiKeyCreateDTO request) {
        return new Result<PublicConversationApiKeyVO>().ok(service.create(SecurityUser.getUserId(), request));
    }

    @GetMapping
    @RequiresPermissions("sys:role:normal")
    public Result<List<PublicConversationApiKeyVO>> list() {
        return new Result<List<PublicConversationApiKeyVO>>().ok(service.list(SecurityUser.getUserId()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> revoke(@PathVariable String id) {
        service.revoke(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }
}
