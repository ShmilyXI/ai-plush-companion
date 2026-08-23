package xiaozhi.modules.conversation.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.service.PublicConversationHistoryStore;
import xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO;

@RestController
@RequestMapping("/internal/public-conversations")
public class InternalPublicConversationController {
    private final PublicConversationService service;
    private final PublicConversationHistoryStore history;

    public InternalPublicConversationController(PublicConversationService service) {
        this(service, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InternalPublicConversationController(PublicConversationService service, PublicConversationHistoryStore history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping("/{id}/bundle")
    public Result<PublicConversationRuntimeBundleVO> bundle(@PathVariable String id) {
        return new Result<PublicConversationRuntimeBundleVO>().ok(service.runtimeBundle(id));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/history")
    public Result<Void> appendHistory(@PathVariable String id, @RequestBody Map<String, Object> item) {
        if (history == null) throw new IllegalStateException("历史存储未配置");
        history.append(id, item);
        return new Result<Void>().ok(null);
    }

    @GetMapping("/{id}/history")
    public Result<List<Map<String, Object>>> history(@PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        if (history == null) throw new IllegalStateException("历史存储未配置");
        return new Result<List<Map<String, Object>>>().ok(history.history(id, limit));
    }
}
