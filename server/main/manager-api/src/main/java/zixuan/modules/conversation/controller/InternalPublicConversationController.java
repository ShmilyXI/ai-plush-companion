package zixuan.modules.conversation.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import zixuan.common.utils.Result;
import zixuan.modules.conversation.service.PublicConversationService;
import zixuan.modules.conversation.service.ConversationThreadService;
import zixuan.modules.conversation.service.PublicConversationHistoryStore;
import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;

@RestController
@RequestMapping("/internal/public-conversations")
public class InternalPublicConversationController {
    private final PublicConversationService service;
    private final PublicConversationHistoryStore history;
    private final ConversationThreadService threads;

    public InternalPublicConversationController(PublicConversationService service) {
        this(service, null, null);
    }

    public InternalPublicConversationController(PublicConversationService service, PublicConversationHistoryStore history) {
        this(service, history, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InternalPublicConversationController(PublicConversationService service, PublicConversationHistoryStore history,
            ConversationThreadService threads) {
        this.service = service;
        this.history = history;
        this.threads = threads;
    }

    @GetMapping("/{id}/bundle")
    public Result<PublicConversationRuntimeBundleVO> bundle(@PathVariable String id) {
        return new Result<PublicConversationRuntimeBundleVO>().ok(service.runtimeBundle(id));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/history")
    public Result<Void> appendHistory(@PathVariable String id, @RequestBody Map<String, Object> item) {
        if (history == null) throw new IllegalStateException("历史存储未配置");
        history.append(id, item);
        // 第一方用户的对话同时计入“用户 + 角色”的对话记录，供之后的新会话接上。
        if (threads != null) threads.append(service.runtimeBundle(id), item);
        return new Result<Void>().ok(null);
    }

    @GetMapping("/{id}/thread")
    public Result<List<Map<String, Object>>> thread(@PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        PublicConversationRuntimeBundleVO bundle = service.runtimeBundle(id);
        if (threads == null || !threads.enabled(bundle)) {
            return new Result<List<Map<String, Object>>>().ok(List.of());
        }
        return new Result<List<Map<String, Object>>>().ok(threads.recent(bundle.ownerId(), bundle.agentId(), limit));
    }

    @GetMapping("/{id}/history")
    public Result<List<Map<String, Object>>> history(@PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        if (history == null) throw new IllegalStateException("历史存储未配置");
        return new Result<List<Map<String, Object>>>().ok(history.history(id, limit));
    }
}
