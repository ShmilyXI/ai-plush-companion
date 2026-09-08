package zixuan.modules.appauth.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.redis.RedisKeys;
import zixuan.common.redis.RedisUtils;
import zixuan.common.utils.ConvertUtils;
import zixuan.common.utils.Sm2DecryptUtil;
import zixuan.modules.appauth.dto.AppCodeLoginDTO;
import zixuan.modules.appauth.dto.AppPasswordLoginDTO;
import zixuan.modules.appauth.dto.AppRegisterDTO;
import zixuan.modules.appauth.service.AppAuthService;
import zixuan.modules.appauth.service.AppUserTokenService;
import zixuan.modules.appauth.vo.AppProfileVO;
import zixuan.modules.appauth.vo.AppTokenVO;
import zixuan.modules.security.password.PasswordUtils;
import zixuan.modules.security.service.CaptchaService;
import zixuan.modules.sys.dto.SysUserDTO;
import zixuan.modules.sys.service.SysParamsService;
import zixuan.modules.sys.service.SysUserService;
import zixuan.common.validator.ValidatorUtils;

/**
 * 消费者 App 认证实现：手机号+验证码、手机号+密码。
 * 手机号统一归一化为国际格式（如 +8613800138000）后使用，
 * 输入 13800138000、+8613800138000 与 +86 0138-0013-8000 视为同一号码。
 */
@AllArgsConstructor
@Service
public class AppAuthServiceImpl implements AppAuthService {

    /** 密码登录失败窗口：60 秒内最多 10 次 */
    private static final long LOGIN_FAILURE_WINDOW_SECONDS = 60;
    private static final long LOGIN_FAILURE_MAX = 10;
    /** 验证码发送按来源IP的当日上限 */
    private static final long SMS_IP_DAILY_MAX = 20;
    private static final long SMS_IP_WINDOW_SECONDS = 3600 * 24;

    private final SysUserService sysUserService;
    private final CaptchaService captchaService;
    private final AppUserTokenService appUserTokens;
    private final SysParamsService sysParamsService;
    private final RedisUtils redisUtils;

    @Override
    public void sendSmsCode(String phone, String ip) {
        phone = normalizePhone(phone);
        if (StringUtils.isNotBlank(ip)) {
            Object count = redisUtils.get(RedisKeys.getAppAuthSmsIpCountKey(ip));
            if (count != null && Long.parseLong(String.valueOf(count)) >= SMS_IP_DAILY_MAX) {
                throw new RenException(ErrorCode.APP_SMS_IP_LIMIT);
            }
            redisUtils.increment(RedisKeys.getAppAuthSmsIpCountKey(ip), SMS_IP_WINDOW_SECONDS);
        }
        // 复用平台短信通道：自带 60 秒间隔、每手机号每日上限与真实下发
        captchaService.sendSMSValidateCode(phone);
    }

    @Override
    public AppTokenVO register(AppRegisterDTO dto, String deviceLabel, String ip) {
        String phone = normalizePhone(dto.getPhone());
        if (!captchaService.validateSMSValidateCode(phone, dto.getCode(), true)) {
            throw new RenException(ErrorCode.SMS_CODE_ERROR);
        }
        if (sysUserService.getByPhone(phone) != null) {
            throw new RenException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }

        String password = Sm2DecryptUtil.decryptLoginPassword(dto.getPassword(), null, sysParamsService);
        SysUserDTO user = new SysUserDTO();
        user.setUsername(phone);
        user.setPhone(phone);
        user.setPassword(password);
        sysUserService.saveAppUser(user);

        return issueToken(phone, deviceLabel, ip);
    }

    @Override
    public AppTokenVO loginByPassword(AppPasswordLoginDTO dto, String deviceLabel, String ip) {
        String phone = normalizePhone(dto.getPhone());
        String failureKey = RedisKeys.getAppAuthLoginFailureKey(phone);
        Object failures = redisUtils.get(failureKey);
        if (failures != null && Long.parseLong(String.valueOf(failures)) >= LOGIN_FAILURE_MAX) {
            throw new RenException(ErrorCode.APP_LOGIN_LOCKED);
        }

        SysUserDTO user = sysUserService.getByPhone(phone);
        String password = Sm2DecryptUtil.decryptLoginPassword(dto.getPassword(), dto.getCaptchaId(),
                sysParamsService);
        if (user == null) {
            redisUtils.increment(failureKey, LOGIN_FAILURE_WINDOW_SECONDS);
            throw new RenException(ErrorCode.PHONE_NOT_REGISTERED);
        }
        if (StringUtils.isBlank(user.getPassword())
                || !PasswordUtils.matches(password, user.getPassword())) {
            redisUtils.increment(failureKey, LOGIN_FAILURE_WINDOW_SECONDS);
            throw new RenException(ErrorCode.ACCOUNT_PASSWORD_ERROR);
        }

        redisUtils.delete(failureKey);
        return appUserTokens.createSession(user.getId(), deviceLabel, ip);
    }

    @Override
    public AppTokenVO loginBySmsCode(AppCodeLoginDTO dto, String deviceLabel, String ip) {
        String phone = normalizePhone(dto.getPhone());
        if (!captchaService.validateSMSValidateCode(phone, dto.getCode(), true)) {
            throw new RenException(ErrorCode.SMS_CODE_ERROR);
        }

        SysUserDTO user = sysUserService.getByPhone(phone);
        if (user == null) {
            // 验证码直登即注册：验证码已验证通过，创建无密码的普通用户
            SysUserDTO created = new SysUserDTO();
            created.setUsername(phone);
            created.setPhone(phone);
            sysUserService.saveAppUser(created);
            user = sysUserService.getByPhone(phone);
            if (user == null) {
                throw new RenException(ErrorCode.PHONE_NOT_REGISTERED);
            }
        }
        return appUserTokens.createSession(user.getId(), deviceLabel, ip);
    }

    @Override
    public AppTokenVO refresh(String refreshToken) {
        return appUserTokens.refresh(refreshToken);
    }

    @Override
    public void logout(String accessToken) {
        appUserTokens.revoke(accessToken);
    }

    @Override
    public AppProfileVO profile(Long userId) {
        SysUserDTO user = sysUserService.getByUserId(userId);
        if (user == null) {
            throw new RenException(ErrorCode.TOKEN_INVALID);
        }
        return ConvertUtils.sourceToTarget(user, AppProfileVO.class);
    }

    private AppTokenVO issueToken(String phone, String deviceLabel, String ip) {
        SysUserDTO user = sysUserService.getByPhone(phone);
        if (user == null) {
            throw new RenException(ErrorCode.PHONE_NOT_REGISTERED);
        }
        return appUserTokens.createSession(user.getId(), deviceLabel, ip);
    }

    /**
     * 归一化手机号：国内 11 位与 +86 前缀折叠为同一存储格式，其他国际号码保持原样。
     */
    private String normalizePhone(String raw) {
        if (raw == null) {
            throw new RenException(ErrorCode.PHONE_FORMAT_ERROR);
        }
        String phone = raw.replaceAll("[\\s-]", "");
        // +8613800138000 / 008613800138000 → +8613800138000
        String mainland = phone.replaceFirst("^\\+86", "").replaceFirst("^0086", "");
        if (mainland.matches("^1[3-9]\\d{9}$")) {
            return "+86" + mainland;
        }
        if (ValidatorUtils.isValidPhone(phone)) {
            return phone;
        }
        throw new RenException(ErrorCode.PHONE_FORMAT_ERROR);
    }
}
