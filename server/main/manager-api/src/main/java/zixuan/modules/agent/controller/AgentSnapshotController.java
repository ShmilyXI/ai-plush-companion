package zixuan.modules.agent.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import zixuan.common.exception.RenException;
import zixuan.common.page.PageData;
import zixuan.common.user.UserDetail;
import zixuan.common.utils.Result;
import zixuan.modules.agent.dto.AgentSnapshotPageDTO;
import zixuan.modules.agent.dto.AgentSnapshotRestoreDTO;
import zixuan.modules.agent.service.AgentService;
import zixuan.modules.agent.service.AgentSnapshotService;
import zixuan.modules.agent.vo.AgentSnapshotVO;
import zixuan.modules.security.user.SecurityUser;

@Tag(name = "智能体快照")
@AllArgsConstructor
@RestController
@RequestMapping("/agent/{agentId}/snapshots")
public class AgentSnapshotController {
    private final AgentSnapshotService agentSnapshotService;
    private final AgentService agentService;

    @GetMapping
    @Operation(summary = "获取智能体快照列表")
    @RequiresPermissions("sys:role:normal")
    public Result<PageData<AgentSnapshotVO>> page(
            @PathVariable String agentId,
            @ParameterObject AgentSnapshotPageDTO params) {
        checkPermission(agentId);
        return new Result<PageData<AgentSnapshotVO>>().ok(agentSnapshotService.page(agentId, params));
    }

    @GetMapping("/{snapshotId}")
    @Operation(summary = "获取智能体快照详情")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentSnapshotVO> getSnapshot(@PathVariable String agentId, @PathVariable String snapshotId) {
        checkPermission(agentId);
        return new Result<AgentSnapshotVO>().ok(agentSnapshotService.getSnapshot(agentId, snapshotId));
    }

    @PostMapping("/{snapshotId}/restore")
    @Operation(summary = "恢复智能体快照")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> restore(@PathVariable String agentId, @PathVariable String snapshotId,
            @RequestBody @Valid AgentSnapshotRestoreDTO request) {
        checkPermission(agentId);
        agentSnapshotService.restoreSnapshot(agentId, snapshotId, request.getCurrentStateToken());
        return new Result<>();
    }

    @PostMapping("/{snapshotId}/activate")
    @Operation(summary = "激活智能体配置版本")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> activate(@PathVariable String agentId, @PathVariable String snapshotId) {
        checkPermission(agentId);
        agentService.activateVersion(agentId, snapshotId, SecurityUser.getUserId());
        return new Result<>();
    }

    @PostMapping("/publish")
    @Operation(summary = "发布智能体草稿")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> publish(@PathVariable String agentId) {
        checkPermission(agentId);
        agentService.publishVersion(agentId, SecurityUser.getUserId());
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/{snapshotId}")
    @Operation(summary = "删除智能体历史快照")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> deleteSnapshot(@PathVariable String agentId, @PathVariable String snapshotId) {
        checkPermission(agentId);
        agentSnapshotService.deleteSnapshot(agentId, snapshotId);
        return new Result<>();
    }

    private void checkPermission(String agentId) {
        UserDetail user = SecurityUser.getUser();
        if (user == null || !agentService.checkAgentPermission(agentId, user.getId())) {
            throw new RenException("没有权限访问该智能体快照");
        }
    }
}
