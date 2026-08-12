package xiaozhi.modules.volcengine.voice;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.Result;

@RestController
@RequestMapping("/volcengine/voices")
@RequiredArgsConstructor
public class VolcengineVoiceCatalogController {
    private final VolcengineVoiceCatalogService service;

    @GetMapping
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<VolcengineVoiceDTO>> list(
            @RequestParam String resourceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String voiceType) {
        if (page < 1 || limit < 1 || limit > 100) {
            throw new RenException("分页参数错误");
        }
        return new Result<PageData<VolcengineVoiceDTO>>().ok(
                service.list(resourceId, page, limit, name, voiceType));
    }
}
