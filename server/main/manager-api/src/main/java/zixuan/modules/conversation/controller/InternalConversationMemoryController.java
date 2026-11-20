package zixuan.modules.conversation.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.AllArgsConstructor;
import zixuan.common.utils.Result;
import zixuan.modules.conversation.service.ConversationMemoryService;
import zixuan.modules.conversation.service.ConversationThreadService;
import zixuan.modules.conversation.service.PublicConversationService;
import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;

/**
 * 对话服务读取和记下角色的记忆。只对第一方登录用户的会话开放：
 * API Key 背后是很多不同的终端用户，他们的事不能记在一起。
 */
@AllArgsConstructor
@RestController
@RequestMapping("/internal/public-conversations")
public class InternalConversationMemoryController {
    private final PublicConversationService service;
    private final ConversationThreadService threads;
    private final ConversationMemoryService memories;

    @GetMapping("/{id}/memories")
    public Result<List<String>> list(@PathVariable String id) {
        PublicConversationRuntimeBundleVO bundle = service.runtimeBundle(id);
        if (!threads.enabled(bundle)) {
            return new Result<List<String>>().ok(List.of());
        }
        List<String> contents = new ArrayList<>();
        for (Map<String, Object> item : memories.list(bundle.ownerId(), bundle.agentId())) {
            contents.add(String.valueOf(item.get("content")));
        }
        return new Result<List<String>>().ok(contents);
    }

    @PostMapping("/{id}/memories")
    public Result<Map<String, Integer>> remember(@PathVariable String id, @RequestBody Map<String, Object> body) {
        PublicConversationRuntimeBundleVO bundle = service.runtimeBundle(id);
        if (!threads.enabled(bundle) || !(body.get("items") instanceof List<?> items)) {
            return new Result<Map<String, Integer>>().ok(Map.of("added", 0));
        }
        List<String> contents = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof String text) {
                contents.add(text);
            }
        }
        int added = memories.remember(bundle.ownerId(), bundle.agentId(), contents,
                ConversationMemoryService.FROM_CONVERSATION);
        return new Result<Map<String, Integer>>().ok(Map.of("added", added));
    }
}
