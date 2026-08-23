package xiaozhi.modules.conversation.controller;

import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.service.PublicConversationAuthService;
import xiaozhi.modules.conversation.service.PublicConversationHistoryStore;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "公共对话历史", description = "读取当前调用方有权访问的文字对话历史")
@SecurityRequirement(name = "publicApiKey")
public class PublicConversationHistoryController {
    private final PublicConversationService conversations;
    private final PublicConversationHistoryStore history;
    private final PublicConversationAuthService auth;

    public PublicConversationHistoryController(PublicConversationService conversations,
            PublicConversationHistoryStore history, PublicConversationAuthService auth) {
        this.conversations = conversations;
        this.history = history;
        this.auth = auth;
    }

    @GetMapping("/{id}/history")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "读取公共对话历史")
    public Result<List<Map<String, Object>>> history(@PathVariable String id,
            @RequestParam(defaultValue = "20") int limit) {
        PublicConversationRuntimeBundleVO bundle = conversations.runtimeBundle(id);
        PublicConversationAuthService.AuthenticatedCaller caller = auth.current();
        if (caller == null || bundle.ownerId() == null || !bundle.ownerId().equals(caller.userId())
                || !caller.canUseAgent(bundle.agentId())) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        if (caller.apiKey()) auth.requireScope(caller, "resource:read");
        return new Result<List<Map<String, Object>>>().ok(history.history(id, limit));
    }
}
