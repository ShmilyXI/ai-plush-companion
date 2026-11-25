package zixuan.modules.appauth.vo;

import java.util.Date;

import lombok.Data;

/**
 * App 当前登录用户信息
 */
@Data
public class AppProfileVO {
    private Long id;
    private String username;
    private String phone;
    private Date createDate;
    /** 有没有设置过密码。以前用验证码直接登录建的账号没有，个人中心据此显示“设置密码”或“修改密码”。 */
    private boolean hasPassword;
    /** 自己设的昵称；没设时为 null，界面显示打码的手机号。 */
    private String nickname;
    /** 自己设的头像（data URL）；没设时为 null。 */
    private String avatar;
}
