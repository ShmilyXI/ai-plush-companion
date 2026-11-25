package zixuan.modules.appauth.entity;

import lombok.Data;

/**
 * App 用户自己设的昵称和头像。没设过的用户没有这一行。
 */
@Data
public class AppUserProfileEntity {
    private Long userId;
    /** 昵称；null 表示没设，界面显示打码的手机号。 */
    private String nickname;
    /** 头像的 data URL；null 表示没设。 */
    private String avatar;
}
