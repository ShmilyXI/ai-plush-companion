package xiaozhi.modules.companion.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import xiaozhi.modules.companion.service.AppProfileFacade;
import xiaozhi.modules.companion.vo.AppAvatarContent;
import xiaozhi.modules.security.user.SecurityUser;

/** Owner-scoped avatar bytes. The checksum in the URL makes responses
 * immutable while the facade still enforces profile ownership. */
@RestController
@RequestMapping("/app/assets/avatars")
public class AppProfileAvatarResourceController {
    private final AppProfileFacade facade;

    public AppProfileAvatarResourceController(AppProfileFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/{profileId}/{checksum}")
    @RequiresPermissions("sys:role:normal")
    public ResponseEntity<byte[]> download(@PathVariable String profileId, @PathVariable String checksum) {
        try {
            AppAvatarContent avatar = facade.loadAvatar(SecurityUser.getUserId(), profileId, checksum);
            MediaType mediaType = MediaType.parseMediaType(avatar.contentType());
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(avatar.content().length)
                    .cacheControl(CacheControl.noCache().cachePrivate().mustRevalidate())
                    .body(avatar.content());
        } catch (RuntimeException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}
