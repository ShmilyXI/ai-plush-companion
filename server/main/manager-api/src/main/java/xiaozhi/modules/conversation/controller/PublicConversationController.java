package xiaozhi.modules.conversation.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.dto.PublicConversationCreateDTO;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.vo.PublicConversationSessionVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/api/v1/conversations")
public class PublicConversationController {
    private final PublicConversationService service;

    public PublicConversationController(PublicConversationService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermissions("sys:role:normal")
    public Result<PublicConversationSessionVO> create(@RequestBody @Valid PublicConversationCreateDTO request) {
        return new Result<PublicConversationSessionVO>().ok(service.create(SecurityUser.getUserId(), request));
    }
}
