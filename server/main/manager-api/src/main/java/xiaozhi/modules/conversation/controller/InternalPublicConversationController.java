package xiaozhi.modules.conversation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import xiaozhi.common.utils.Result;
import xiaozhi.modules.conversation.service.PublicConversationService;
import xiaozhi.modules.conversation.vo.PublicConversationRuntimeBundleVO;

@RestController
@RequestMapping("/internal/public-conversations")
public class InternalPublicConversationController {
    private final PublicConversationService service;

    public InternalPublicConversationController(PublicConversationService service) {
        this.service = service;
    }

    @GetMapping("/{id}/bundle")
    public Result<PublicConversationRuntimeBundleVO> bundle(@PathVariable String id) {
        return new Result<PublicConversationRuntimeBundleVO>().ok(service.runtimeBundle(id));
    }
}
