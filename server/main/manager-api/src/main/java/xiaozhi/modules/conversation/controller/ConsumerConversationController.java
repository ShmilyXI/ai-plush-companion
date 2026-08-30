package xiaozhi.modules.conversation.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.entity.CompanionConversationEntity;
import xiaozhi.modules.conversation.service.CompanionConversationIndexService;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.vo.PublicConversationSessionVO;
import xiaozhi.modules.security.user.SecurityUser;

/** Owner-scoped conversation index used by the first-party mobile app. */
@RestController
@AllArgsConstructor
@RequestMapping("/api/v1/conversations")
public class ConsumerConversationController {
    private final CompanionConversationIndexService index;
    private final PublicConversationService runtime;

    @GetMapping
    @RequiresPermissions("sys:role:normal")
    public Result<List<CompanionConversationEntity>> list() {
        return new Result<List<CompanionConversationEntity>>().ok(index.list(SecurityUser.getUserId(), 100));
    }

    @PostMapping("/{id}/runtime")
    @RequiresPermissions("sys:role:normal")
    public Result<PublicConversationSessionVO> continueRuntime(@PathVariable String id) {
        return new Result<PublicConversationSessionVO>().ok(runtime.continueConversation(SecurityUser.getUserId(), id));
    }

    @PatchMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> rename(@PathVariable String id, @RequestBody @Valid RenameRequest request) {
        index.rename(SecurityUser.getUserId(), id, request.title());
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> delete(@PathVariable String id) {
        index.softDelete(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }

    public record RenameRequest(@NotBlank @Size(max = 80) String title) {
    }
}
