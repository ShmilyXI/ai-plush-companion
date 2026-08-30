package xiaozhi.modules.companion.controller;

import java.io.IOException;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.companion.service.AppProfileFacade;
import xiaozhi.modules.companion.vo.AppAvatarVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@AllArgsConstructor
@RequestMapping("/app/profiles")
public class AppProfileAvatarController {
    private final AppProfileFacade facade;

    @PostMapping(value = "/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermissions("sys:role:normal")
    public Result<AppAvatarVO> upload(@PathVariable String id, @RequestPart("file") MultipartFile file) {
        if (file == null) throw new RenException("头像文件不能为空");
        try {
            return new Result<AppAvatarVO>().ok(facade.saveAvatar(
                    SecurityUser.getUserId(), id, file.getBytes(), file.getContentType()));
        } catch (IOException exception) {
            throw new RenException("头像读取失败");
        }
    }
}
