package zixuan.modules.appauth.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import zixuan.modules.appauth.dao.AccountDataDao;
import zixuan.modules.appauth.dao.AppUserProfileDao;
import zixuan.modules.appauth.entity.AppUserProfileEntity;
import zixuan.modules.invite.service.InviteCodeService;
import org.springframework.transaction.annotation.Transactional;

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
    /** 一个验证码最多允许输错的次数，超过后验证码作废，需要重新获取 */
    private static final long SMS_CODE_MAX_FAILURES = 5;
    private static final long SMS_CODE_FAILURE_WINDOW_SECONDS = 300;

    private final SysUserService sysUserService;
    private final CaptchaService captchaService;
    private final AppUserTokenService appUserTokens;
    private final SysParamsService sysParamsService;
    private final RedisUtils redisUtils;
    private final InviteCodeService inviteCodes;
    private final AccountDataDao accountData;
    private final AppUserProfileDao profiles;

    /** 昵称最多几个字（按字符算，一个表情算一个）。和 app_user_profile.nickname 的长度一致。 */
    static final int NICKNAME_MAX = 20;
    /** 头像 data URL 最长多少个字符。浏览器会先缩成 256 像素的正方形，正常只有几十 KB，这里留足余量。 */
    static final int AVATAR_MAX_CHARS = 300_000;

    /** 注册时设置的密码：8 到 32 位，同时有大写字母、小写字母和数字。 */
    static final String PASSWORD_RULE = "密码需要 8 到 32 位，同时包含大写字母、小写字母和数字";
    static final String NO_PASSWORD_YET = "这个账号还没有设置密码，请用验证码登录";

    static boolean acceptablePassword(String password) {
        return password != null && password.length() >= 8 && password.length() <= 32
                && password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit);
    }

    @Override
    public void sendLoginSmsCode(String phone, String ip, String captchaId, String captcha) {
        // 先核对图形验证码，并且无论对错都作废，猜错要换一张新的。
        if (StringUtils.isBlank(captchaId) || !captchaService.validate(captchaId, captcha, true)) {
            throw new RenException(ErrorCode.SMS_CAPTCHA_ERROR);
        }
        sendSmsCode(phone, ip);
    }

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
    @Transactional(rollbackFor = Exception.class)
    public AppTokenVO register(AppRegisterDTO dto, String deviceLabel, String ip) {
        String phone = normalizePhone(dto.getPhone());
        verifySmsCode(phone, dto.getCode());
        if (sysUserService.getByPhone(phone) != null) {
            throw new RenException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        boolean inviteRequired = inviteCodes.isRequired();
        if (inviteRequired) {
            inviteCodes.requireUsable(dto.getInviteCode());
        }
        String password = Sm2DecryptUtil.decryptLoginPassword(dto.getPassword(), null, sysParamsService);
        if (!acceptablePassword(password)) {
            throw new RenException(PASSWORD_RULE);
        }
        SysUserDTO user = new SysUserDTO();
        user.setUsername(phone);
        user.setPhone(phone);
        user.setPassword(password);
        sysUserService.saveAppUser(user);
        SysUserDTO created = sysUserService.getByPhone(phone);
        if (created == null) {
            throw new RenException(ErrorCode.PHONE_NOT_REGISTERED);
        }
        if (inviteRequired) {
            inviteCodes.redeem(dto.getInviteCode(), created.getId());
        }
        consumeSmsCode(phone, dto.getCode());
        recordConsent(created.getId(), dto.getConsentVersion());
        return appUserTokens.createSession(created.getId(), deviceLabel, ip);
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
        if (StringUtils.isBlank(user.getPassword())) {
            // 以前用验证码直接登录建的账号没有密码。
            redisUtils.increment(failureKey, LOGIN_FAILURE_WINDOW_SECONDS);
            throw new RenException(NO_PASSWORD_YET);
        }
        if (!PasswordUtils.matches(password, user.getPassword())) {
            redisUtils.increment(failureKey, LOGIN_FAILURE_WINDOW_SECONDS);
            throw new RenException(ErrorCode.ACCOUNT_PASSWORD_ERROR);
        }

        redisUtils.delete(failureKey);
        return appUserTokens.createSession(user.getId(), deviceLabel, ip);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppTokenVO loginBySmsCode(AppCodeLoginDTO dto, String deviceLabel, String ip) {
        String phone = normalizePhone(dto.getPhone());
        // 先确认验证码正确但不作废：只有证明了手机号归属，才会告诉对方“需要邀请码”，
        // 而且邀请码填错时不用重新收一次短信。
        verifySmsCode(phone, dto.getCode());
        SysUserDTO user = sysUserService.getByPhone(phone);
        if (user == null) {
            // 验证码登录不再顺便注册：注册要设密码，走 register。验证码这时不作废，
            // 网页端转到注册页时可以接着用同一个验证码，不用再收一次短信。
            throw new RenException(ErrorCode.PHONE_NOT_REGISTERED);
        }
        consumeSmsCode(phone, dto.getCode());
        recordConsent(user.getId(), dto.getConsentVersion());
        return appUserTokens.createSession(user.getId(), deviceLabel, ip);
    }

    /** 记下用户同意的是哪一版协议。同一版只记一次。 */
    private void recordConsent(Long userId, String version) {
        String normalized = StringUtils.trimToNull(version);
        if (normalized != null && normalized.matches("[A-Za-z0-9._-]{1,32}")) {
            accountData.recordConsent(userId, normalized);
        }
    }

    @Override
    public void sendAccountCode(Long userId, String ip) {
        sendSmsCode(requireAccountPhone(userId), ip);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAccount(Long userId, String code) {
        SysUserDTO user = sysUserService.getByUserId(userId);
        if (user == null) {
            throw new RenException(ErrorCode.TOKEN_INVALID);
        }
        if (Integer.valueOf(1).equals(user.getSuperAdmin())) {
            // 管理员账号承载着整个平台的配置，不能从用户入口注销。
            throw new RenException("管理员账号不能在这里注销");
        }
        String phone = requireAccountPhone(userId);
        // 注销不可恢复，必须再用一次短信验证码确认是本人，光有登录状态不够。
        verifySmsCode(phone, code);
        consumeSmsCode(phone, code);

        accountData.deleteConversationTurns(userId);
        accountData.deleteConversationMemories(userId);
        accountData.anonymizeUsage(userId);
        accountData.anonymizeSafetyEvents(userId);
        accountData.deleteClientEvents(userId);
        accountData.deleteInviteUse(userId);
        accountData.deleteConsents(userId);
        accountData.deleteModelCredentials(userId);
        accountData.deletePrivateModels(userId);
        accountData.deleteSubscriptions(userId);
        accountData.deleteApiKeys(userId);
        accountData.deleteVoiceClones(userId);
        accountData.deleteScreenScenes(userId);
        accountData.deleteAppTokens(userId);
        accountData.deleteConsoleTokens(userId);
        profiles.delete(userId);
        // 最后删除账号本身，连同它的角色和设备绑定。
        sysUserService.deleteById(userId);
    }

    @Override
    public void checkAccountCode(Long userId, String code) {
        verifySmsCode(requireAccountPhone(userId), code);
    }

    @Override
    public void setPassword(Long userId, String code, String encryptedPassword) {
        SysUserDTO user = sysUserService.getByUserId(userId);
        if (user == null) {
            throw new RenException(ErrorCode.TOKEN_INVALID);
        }
        if (Integer.valueOf(1).equals(user.getSuperAdmin())) {
            throw new RenException("管理员账号请在管理台修改密码");
        }
        String phone = requireAccountPhone(userId);
        // 光有登录状态不够，要再用一次验证码确认是本人，这样忘了旧密码也能改。
        verifySmsCode(phone, code);
        String password = Sm2DecryptUtil.decryptLoginPassword(encryptedPassword, null, sysParamsService);
        if (!acceptablePassword(password)) {
            throw new RenException(PASSWORD_RULE);
        }
        sysUserService.changePasswordDirectly(userId, password);
        consumeSmsCode(phone, code);
        // 之前输错密码记下的次数清掉，新密码马上能用。
        redisUtils.delete(RedisKeys.getAppAuthLoginFailureKey(phone));
    }

    private String requireAccountPhone(Long userId) {
        SysUserDTO user = sysUserService.getByUserId(userId);
        if (user == null) {
            throw new RenException(ErrorCode.TOKEN_INVALID);
        }
        String phone = StringUtils.defaultIfBlank(user.getPhone(), user.getUsername());
        return normalizePhone(phone);
    }

    /**
     * 校验短信验证码但不作废。同一个验证码最多允许输错 5 次，超过后作废，
     * 这样手滑输错一位不用重新收短信，暴力尝试也只有 5 次机会。
     */
    private void verifySmsCode(String phone, String code) {
        if (captchaService.validateSMSValidateCode(phone, code, false)) {
            return;
        }
        String failureKey = RedisKeys.getAppAuthSmsCodeFailureKey(phone);
        Long failures = redisUtils.increment(failureKey, SMS_CODE_FAILURE_WINDOW_SECONDS);
        if (failures != null && failures >= SMS_CODE_MAX_FAILURES) {
            // 带作废标记再校验一次，用来删除缓存里的验证码。
            captchaService.validateSMSValidateCode(phone, "-", true);
            redisUtils.delete(failureKey);
        }
        throw new RenException(ErrorCode.SMS_CODE_ERROR);
    }

    private void consumeSmsCode(String phone, String code) {
        captchaService.validateSMSValidateCode(phone, code, true);
        redisUtils.delete(RedisKeys.getAppAuthSmsCodeFailureKey(phone));
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
        AppProfileVO profile = ConvertUtils.sourceToTarget(user, AppProfileVO.class);
        profile.setHasPassword(StringUtils.isNotBlank(user.getPassword()));
        AppUserProfileEntity own = profiles.find(userId);
        if (own != null) {
            profile.setNickname(own.getNickname());
            profile.setAvatar(own.getAvatar());
        }
        return profile;
    }

    @Override
    public AppProfileVO updateProfile(Long userId, String nickname, String avatar) {
        if (sysUserService.getByUserId(userId) == null) {
            throw new RenException(ErrorCode.TOKEN_INVALID);
        }
        // 先把两项都检查完再写，免得昵称写进去了、头像却被拒。
        String cleanNickname = nickname == null ? null : cleanNickname(nickname);
        String cleanAvatar = avatar == null ? null : cleanAvatar(avatar);
        if (nickname != null) {
            profiles.saveNickname(userId, cleanNickname);
        }
        if (avatar != null) {
            profiles.saveAvatar(userId, cleanAvatar);
        }
        return profile(userId);
    }

    /** 去掉首尾空白；空的表示不用昵称（返回 null）。太长或含换行、控制字符的拒绝。 */
    static String cleanNickname(String raw) {
        String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        boolean special = value.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR);
        if (special || value.codePointCount(0, value.length()) > NICKNAME_MAX) {
            throw new RenException(ErrorCode.APP_NICKNAME_INVALID);
        }
        return value;
    }

    /**
     * 空的表示移除头像（返回 null）。其余必须是 JPEG、PNG 或 WebP 的 base64 data URL，
     * 不超过长度上限，并且解出来的文件头和声明的类型一致。
     */
    static String cleanAvatar(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        int comma = value.indexOf(',');
        if (value.length() > AVATAR_MAX_CHARS || comma < 0) {
            throw new RenException(ErrorCode.APP_AVATAR_INVALID);
        }
        String type = switch (value.substring(0, comma)) {
            case "data:image/jpeg;base64" -> "jpeg";
            case "data:image/png;base64" -> "png";
            case "data:image/webp;base64" -> "webp";
            default -> throw new RenException(ErrorCode.APP_AVATAR_INVALID);
        };
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(value.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new RenException(ErrorCode.APP_AVATAR_INVALID);
        }
        if (!imageMatches(type, bytes)) {
            throw new RenException(ErrorCode.APP_AVATAR_INVALID);
        }
        return value;
    }

    private static boolean imageMatches(String type, byte[] b) {
        return switch (type) {
            case "jpeg" -> b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
            case "png" -> b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "webp" -> b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            default -> false;
        };
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
