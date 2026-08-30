package xiaozhi.modules.conversation.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.service.PublicConversationHistoryStore;
import xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO;
import xiaozhi.modules.conversation.service.CompanionConversationIndexService;

@RestController
@RequestMapping("/internal/public-conversations")
public class InternalPublicConversationController {
    private final PublicConversationService service;
    private final PublicConversationHistoryStore history;
    private final CompanionConversationIndexService conversationIndex;

    public InternalPublicConversationController(PublicConversationService service) {
        this(service, null, null);
    }

    public InternalPublicConversationController(PublicConversationService service, PublicConversationHistoryStore history) {
        this(service, history, null);
    }

    @Autowired
    public InternalPublicConversationController(PublicConversationService service, PublicConversationHistoryStore history,
            CompanionConversationIndexService conversationIndex) {
        this.service = service;
        this.history = history;
        this.conversationIndex = conversationIndex;
    }

    @GetMapping("/{id}/bundle")
    public Result<PublicConversationRuntimeBundleVO> bundle(@PathVariable String id) {
        return new Result<PublicConversationRuntimeBundleVO>().ok(service.runtimeBundle(id));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/history")
    public Result<Void> appendHistory(@PathVariable String id, @RequestBody Map<String, Object> item) {
        if (history == null) throw new IllegalStateException("历史存储未配置");
        history.append(id, item);
        if (conversationIndex != null) {
            PublicConversationRuntimeBundleVO bundle = service.runtimeBundle(id);
            Object turnId = item == null ? null : item.get("turn_id");
            Object requestId = item == null ? null : item.get("request_id");
            Object text = item == null ? null : (item.containsKey("text") ? item.get("text") : item.get("user_text"));
            Object reply = item == null ? null : (item.containsKey("reply") ? item.get("reply") : item.get("assistant_text"));
            Object occurredAt = item == null ? null : item.get("occurred_at");
            if (turnId instanceof String t && text instanceof String u && reply instanceof String a
                    && occurredAt instanceof Number time) {
                conversationIndex.appendTurnIfAbsent(bundle.ownerId(), id, t,
                        requestId instanceof String r ? r : null, u, a,
                        item.get("source") instanceof String source ? source : "device",
                        new java.util.Date(time.longValue()));
            }
        }
        return new Result<Void>().ok(null);
    }

    @GetMapping("/{id}/history")
    public Result<List<Map<String, Object>>> history(@PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        if (history == null) throw new IllegalStateException("历史存储未配置");
        return new Result<List<Map<String, Object>>>().ok(history.history(id, limit));
    }
}
