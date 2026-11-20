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
import zixuan.modules.conversation.service.ConversationSafetyService;
import zixuan.modules.conversation.service.ConversationThreadService;
import zixuan.modules.conversation.service.ConversationUsageService;
import zixuan.modules.conversation.service.PublicConversationHistoryStore;
import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;

@RestController
@RequestMapping("/internal/public-conversations")
public class InternalPublicConversationController {
    private final PublicConversationService service;
    private final PublicConversationHistoryStore history;
    private final ConversationThreadService threads;
    private final ConversationUsageService usage;
    private ConversationSafetyService safety;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSafety(ConversationSafetyService safety) {
        this.safety = safety;
    }

    public InternalPublicConversationController(PublicConversationService service) {
        this(service, null, null);
    }

    public InternalPublicConversationController(PublicConversationService service, PublicConversationHistoryStore history) {
        this(service, history, null, null);
    }

    public InternalPublicConversationController(PublicConversationService service, PublicConversationHistoryStore history,
            ConversationThreadService threads) {
        this(service, history, threads, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InternalPublicConversationController(PublicConversationService service, PublicConversationHistoryStore history,
            ConversationThreadService threads, ConversationUsageService usage) {
        this.service = service;
        this.history = history;
        this.threads = threads;
        this.usage = usage;
    }

    @GetMapping("/{id}/bundle")
    public Result<PublicConversationRuntimeBundleVO> bundle(@PathVariable String id) {
        return new Result<PublicConversationRuntimeBundleVO>().ok(service.runtimeBundle(id));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/history")
    public Result<Map<String, Object>> appendHistory(@PathVariable String id, @RequestBody Map<String, Object> item) {
        if (history == null) throw new IllegalStateException("历史存储未配置");
        // 用量只用于计量，不属于对话内容，不进入任何对话历史。
        Map<String, Object> turn = new java.util.LinkedHashMap<>(item);
        turn.remove("usage");
        // 开场白是角色主动说的，没有用户的话。它不进会话内的临时历史，只进长期的对话记录。
        boolean opening = Boolean.TRUE.equals(item.get("opening"));
        if (!opening) history.append(id, turn);
        PublicConversationRuntimeBundleVO bundle = service.runtimeBundle(id);
        // 第一方用户的对话同时计入“用户 + 角色”的对话记录，供之后的新会话接上。
        if (threads != null) threads.append(bundle, turn);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (usage != null) {
            // 告诉运行时这个用户今天还剩多少轮，用完后运行时不再开始新的一轮。
            Integer remaining = usage.record(bundle, item);
            if (remaining != null) result.put("remaining_turns", remaining);
        }
        return new Result<Map<String, Object>>().ok(result);
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/safety")
    public Result<Void> safetyEvent(@PathVariable String id, @RequestBody Map<String, Object> event) {
        if (safety != null) safety.record(service.runtimeBundle(id), event);
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
