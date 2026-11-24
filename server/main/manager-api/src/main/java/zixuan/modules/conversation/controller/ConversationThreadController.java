package zixuan.modules.conversation.controller;

import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import zixuan.common.utils.Result;
import zixuan.modules.agent.service.AgentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import zixuan.modules.conversation.service.ConversationMemoryService;
import zixuan.modules.conversation.service.ConversationThreadService;
import zixuan.modules.conversation.service.PublicConversationAuthService;
import zixuan.modules.security.user.SecurityUser;

/**
 * 登录用户查看和删除自己与某个角色的对话记录。
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/conversations/threads")
@Tag(name = "对话记录", description = "登录用户与角色之间的对话记录")
public class ConversationThreadController {
    private final ConversationThreadService threads;
    private final AgentService agents;
    private final PublicConversationAuthService auth;
    private final ConversationMemoryService memories;

    @GetMapping("/{agentId}")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "读取与一个角色最近的对话")
    public Result<List<Map<String, Object>>> recent(@PathVariable String agentId,
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(required = false) Long before) {
        return new Result<List<Map<String, Object>>>().ok(threads.recent(requireOwner(agentId), agentId, limit, before));
    }

    @GetMapping("/{agentId}/days")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "按天汇总与一个角色的对话")
    public Result<List<Map<String, Object>>> days(@PathVariable String agentId,
            @RequestParam(defaultValue = "480") int tz) {
        return new Result<List<Map<String, Object>>>().ok(threads.days(requireOwner(agentId), agentId, tz));
    }

    @DeleteMapping("/{agentId}")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "删除与一个角色的全部对话记录")
    public Result<Map<String, Integer>> clear(@PathVariable String agentId) {
        Long userId = requireOwner(agentId);
        // 清空对话记录的意思是“它不再记得之前聊过什么”，所以从对话里记下的事也一起忘掉。
        memories.clear(userId, agentId);
        return new Result<Map<String, Integer>>().ok(Map.of("deleted", threads.clear(userId, agentId)));
    }

    @GetMapping("/{agentId}/memories")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "这个角色记得的关于自己的事")
    public Result<List<Map<String, Object>>> memories(@PathVariable String agentId) {
        return new Result<List<Map<String, Object>>>().ok(memories.list(requireOwner(agentId), agentId));
    }

    @PostMapping("/{agentId}/memories")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "自己写一件事让角色记住")
    public Result<Map<String, Integer>> remember(@PathVariable String agentId, @RequestBody Map<String, Object> body) {
        Long userId = requireOwner(agentId);
        String content = body.get("content") instanceof String text ? text.trim() : "";
        if (content.isEmpty()) {
            throw new IllegalArgumentException("请写下要它记住的事");
        }
        return new Result<Map<String, Integer>>().ok(Map.of("added",
                memories.remember(userId, agentId, List.of(content), ConversationMemoryService.FROM_USER)));
    }

    @DeleteMapping("/{agentId}/memories/{memoryId}")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "让角色忘掉一件事")
    public Result<Map<String, Boolean>> forget(@PathVariable String agentId, @PathVariable Long memoryId) {
        return new Result<Map<String, Boolean>>().ok(Map.of("deleted",
                memories.forget(requireOwner(agentId), agentId, memoryId)));
    }

    @DeleteMapping("/{agentId}/memories")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "让角色忘掉记得的全部")
    public Result<Map<String, Integer>> forgetAll(@PathVariable String agentId) {
        return new Result<Map<String, Integer>>().ok(Map.of("deleted", memories.clear(requireOwner(agentId), agentId)));
    }

    private Long requireOwner(String agentId) {
        PublicConversationAuthService.AuthenticatedCaller caller = auth.current();
        // 对话记录属于登录的人。API Key 背后的终端用户不是同一个人，不能读也不能删。
        if (caller != null && caller.apiKey()) {
            throw new IllegalArgumentException("对话记录只对登录用户开放");
        }
        Long userId = SecurityUser.getUserId();
        if (userId == null || agents.getAgentById(agentId, userId) == null) {
            throw new IllegalArgumentException("角色不存在或无权访问");
        }
        return userId;
    }
}
