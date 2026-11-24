package zixuan.modules.conversation.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import zixuan.modules.conversation.service.PublicConversationAuthService;
import zixuan.modules.timbre.service.VoicePreviewService;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "公共对话资源", description = "供 APP、网页和服务端调用的角色、模型、音色和设备公开元数据")
@SecurityRequirement(name = "publicApiKey")
public class VoicePreviewController {
    private final VoicePreviewService previews;
    private final PublicConversationAuthService auth;

    @Autowired
    public VoicePreviewController(VoicePreviewService previews, PublicConversationAuthService auth) {
        this.previews = previews;
        this.auth = auth;
    }

    @GetMapping("/voices/{voiceId}/preview")
    @RequiresPermissions("sys:role:normal")
    @Operation(summary = "试听音色：返回这个音色读一句固定的话的音频")
    public ResponseEntity<byte[]> preview(@PathVariable String voiceId) {
        if (auth != null) {
            PublicConversationAuthService.AuthenticatedCaller caller = auth.current();
            if (caller.apiKey()) auth.requireScope(caller, "resource:read");
        }
        VoicePreviewService.Preview preview = previews.preview(voiceId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(preview.contentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePrivate())
                .body(preview.audio());
    }
}
