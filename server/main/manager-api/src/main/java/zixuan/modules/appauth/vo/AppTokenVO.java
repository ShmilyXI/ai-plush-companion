package zixuan.modules.appauth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * App 会话令牌：明文只在签发/刷新响应中出现一次
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AppTokenVO {
    private String accessToken;
    private int expiresIn;
    private String refreshToken;
    private int refreshExpiresIn;
}
