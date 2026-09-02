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
import xiaozhi.modules.conversation.service.CompanionConversationIndexService;
import xiaozhi.modules.conversation.entity.CompanionConversationEntity;
import xiaozhi.modules.conversation.entity.CompanionConversationTurnEntity;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "公共对话历史", description = "读取当前调用方有权访问的文字对话历史")
@SecurityRequirement(name = "publicApiKey")
public class PublicConversationHistoryController {
    private final PublicConversationService conversations;
    private final PublicConversationHistoryStore history;
    private final PublicConversationAuthService auth;
    private final CompanionConversationIndexService conversationIndex;

    public PublicConversationHistoryController(PublicConversationService conversations,
            PublicConversationHistoryStore history, PublicConversationAuthService auth) {
        this(conversations, history, auth, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PublicConversationHistoryController(PublicConversationService conversations,
            PublicConversationHistoryStore history, PublicConversationAuthService auth,
            CompanionConversationIndexService conversationIndex) {
        this.conversations = conversations;
        this.history = history;
        this.auth = auth;
        this.conversationIndex = conversationIndex;
    }

    @GetMapping("/{id}/history")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "读取公共对话历史")
    public Result<List<Map<String, Object>>> history(@PathVariable String id,
            @RequestParam(defaultValue = "20") int limit) {
        PublicConversationAuthService.AuthenticatedCaller caller = auth.current();
        if (caller == null) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        if (conversationIndex != null) {
            PublicConversationRuntimeBundleVO bundle = runtimeBundleOrNull(id);
            if (bundle != null && !authorized(caller, bundle.ownerId(), bundle.agentId())) {
                throw new IllegalArgumentException("无权访问该会话");
            }
            if (bundle == null) {
                CompanionConversationEntity durableConversation = conversationIndex.requireReadable(caller.userId(), id);
                if (!authorized(caller, durableConversation.getOwnerId(), durableConversation.getProfileId())) {
                    throw new IllegalArgumentException("无权访问该会话");
                }
            }
            if (caller.apiKey()) auth.requireScope(caller, "resource:read");
            List<Map<String, Object>> durable = conversationIndex.history(caller.userId(), id).stream()
                    .map(PublicConversationHistoryController::toHistoryItem).toList();
            return new Result<List<Map<String, Object>>>().ok(durable.stream().limit(Math.max(limit, 0)).toList());
        }
        PublicConversationRuntimeBundleVO bundle = conversations.runtimeBundle(id);
        if (!authorized(caller, bundle.ownerId(), bundle.agentId())) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        if (caller.apiKey()) auth.requireScope(caller, "resource:read");
        return new Result<List<Map<String, Object>>>().ok(history.history(id, limit));
    }

    private PublicConversationRuntimeBundleVO runtimeBundleOrNull(String id) {
        try {
            return conversations.runtimeBundle(id);
        } catch (IllegalArgumentException expiredOrMissing) {
            return null;
        }
    }

    private boolean authorized(PublicConversationAuthService.AuthenticatedCaller caller,
            Long ownerId, String agentId) {
        return ownerId != null && ownerId.equals(caller.userId()) && caller.canUseAgent(agentId);
    }

    private static Map<String, Object> toHistoryItem(CompanionConversationTurnEntity turn) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("turn_id", turn.getTurnId());
        item.put("request_id", turn.getRequestId());
        item.put("source", turn.getSource());
        item.put("text", turn.getUserText());
        item.put("reply", turn.getAssistantText());
        item.put("occurred_at", turn.getOccurredAt() == null ? null : turn.getOccurredAt().getTime());
        return item;
    }
}
