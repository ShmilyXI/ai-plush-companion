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

    AppTokenVO register(AppRegisterDTO dto, String deviceLabel, String ip);

    AppTokenVO loginByPassword(AppPasswordLoginDTO dto, String deviceLabel, String ip);

    AppTokenVO loginBySmsCode(AppCodeLoginDTO dto, String deviceLabel, String ip);

    AppTokenVO refresh(String refreshToken);

    void logout(String accessToken);

    AppProfileVO profile(Long userId);
}
