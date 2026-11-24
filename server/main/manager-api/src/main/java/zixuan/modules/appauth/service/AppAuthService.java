package zixuan.modules.appauth.service;

import zixuan.modules.appauth.dto.AppCodeLoginDTO;
import zixuan.modules.appauth.dto.AppPasswordLoginDTO;
import zixuan.modules.appauth.dto.AppRegisterDTO;
import zixuan.modules.appauth.vo.AppProfileVO;
import zixuan.modules.appauth.vo.AppTokenVO;

/**
 * 消费者 App 认证：手机号+验证码、手机号+密码
 */
public interface AppAuthService {

    void sendSmsCode(String phone, String ip);

    /**
     * 登录和注册用的验证码：先核对图形验证码，对了才发短信。
     * 图形验证码看一次就作废，防止有人用脚本批量刷我们的短信。已登录用户的验证码（注销账号）不走这里。
     */
    void sendLoginSmsCode(String phone, String ip, String captchaId, String captcha);

    AppTokenVO register(AppRegisterDTO dto, String deviceLabel, String ip);

    AppTokenVO loginByPassword(AppPasswordLoginDTO dto, String deviceLabel, String ip);

    AppTokenVO loginBySmsCode(AppCodeLoginDTO dto, String deviceLabel, String ip);

    AppTokenVO refresh(String refreshToken);

    void logout(String accessToken);

    AppProfileVO profile(Long userId);

    /**
     * 给当前账号的手机号发一个验证码，用于注销前确认是本人。
     */
    void sendAccountCode(Long userId, String ip);

    /**
     * 注销账号。验证码通过后删除这个人的角色、对话记录、凭据和登录会话，
     * 只含数量的统计记录改为匿名。操作不可恢复。
     */
    void deleteAccount(Long userId, String code);
}
