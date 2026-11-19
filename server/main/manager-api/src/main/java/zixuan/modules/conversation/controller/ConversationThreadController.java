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

    @GetMapping("/{agentId}")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "读取与一个角色最近的对话")
    public Result<List<Map<String, Object>>> recent(@PathVariable String agentId,
            @RequestParam(defaultValue = "30") int limit) {
        return new Result<List<Map<String, Object>>>().ok(threads.recent(requireOwner(agentId), agentId, limit));
    }

    @DeleteMapping("/{agentId}")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "删除与一个角色的全部对话记录")
    public Result<Map<String, Integer>> clear(@PathVariable String agentId) {
        return new Result<Map<String, Integer>>().ok(Map.of("deleted", threads.clear(requireOwner(agentId), agentId)));
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
