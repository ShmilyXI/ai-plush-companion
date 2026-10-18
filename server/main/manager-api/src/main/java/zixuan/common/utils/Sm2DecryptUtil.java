package zixuan.common.utils;

import org.apache.commons.lang3.StringUtils;
import zixuan.common.constant.Constant;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.modules.security.service.CaptchaService;
import zixuan.modules.sys.service.SysParamsService;

/**
 * SM2解密和验证码验证工具类
 * 封装了重复的SM2解密、验证码提取和验证逻辑
 */
public class Sm2DecryptUtil {

    /**
     * 验证码长度
     */
    private static final int CAPTCHA_LENGTH = 5;

    /**
     * 解密仅包含密码的SM2密文。
     *
     * @param encryptedPassword SM2加密的密码字符串
     * @param sysParamsService  系统参数服务
     * @return 完整的明文密码
     */
    public static String decryptPassword(String encryptedPassword, SysParamsService sysParamsService) {
        String decryptedPassword = decryptContent(encryptedPassword, sysParamsService);
        if (StringUtils.isBlank(decryptedPassword)) {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }
        return decryptedPassword;
    }

    /**
     * 解密登录密码，兼容仍发送“5位验证码+密码”密文的旧客户端。
     * captchaId仅用于识别旧协议，不恢复图形验证码校验。
     */
    public static String decryptLoginPassword(String encryptedPassword, String captchaId,
            SysParamsService sysParamsService) {
        String decryptedPassword = decryptPassword(encryptedPassword, sysParamsService);
        if (StringUtils.isBlank(captchaId)) {
            return decryptedPassword;
        }
        if (decryptedPassword.length() <= CAPTCHA_LENGTH) {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }

        String legacyPassword = decryptedPassword.substring(CAPTCHA_LENGTH);
        if (StringUtils.isBlank(legacyPassword)) {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }
        return legacyPassword;
    }

    private static String decryptContent(String encryptedContent, SysParamsService sysParamsService) {
        String privateKeyStr;
        try {
            privateKeyStr = sysParamsService.getValue(Constant.SM2_PRIVATE_KEY, true);
        } catch (Exception e) {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }
        if (StringUtils.isBlank(privateKeyStr)) {
            throw new RenException(ErrorCode.SM2_KEY_NOT_CONFIGURED);
        }

        try {
            return SM2Utils.decrypt(privateKeyStr, encryptedContent);
        } catch (Exception e) {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }
    }

    /**
     * 解密SM2加密内容，提取验证码并验证
     * 
     * @param encryptedPassword SM2加密的密码字符串
     * @param captchaId         验证码ID
     * @param captchaService    验证码服务
     * @param sysParamsService  系统参数服务
     * @return 解密后的实际密码
     */
    public static String decryptAndValidateCaptcha(String encryptedPassword, String captchaId,
            CaptchaService captchaService, SysParamsService sysParamsService) {
        // 获取SM2私钥
        String privateKeyStr = sysParamsService.getValue(Constant.SM2_PRIVATE_KEY, true);
        if (StringUtils.isBlank(privateKeyStr)) {
            throw new RenException(ErrorCode.SM2_KEY_NOT_CONFIGURED);
        }

        // 使用SM2私钥解密密码
        String decryptedContent;
        try {
            decryptedContent = SM2Utils.decrypt(privateKeyStr, encryptedPassword);
        } catch (Exception e) {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }

        // 分离验证码和密码：前5位是验证码，后面是密码
        if (decryptedContent.length() > CAPTCHA_LENGTH) {
            String embeddedCaptcha = decryptedContent.substring(0, CAPTCHA_LENGTH);
            String actualPassword = decryptedContent.substring(CAPTCHA_LENGTH);

            boolean embeddedCaptchaValid = captchaService.validate(captchaId, embeddedCaptcha, true);
            if (!embeddedCaptchaValid) {
                throw new RenException(ErrorCode.SMS_CAPTCHA_ERROR);
            }

            return actualPassword;
        } else if (decryptedContent.length() > 0) {
            throw new RenException(ErrorCode.SMS_CAPTCHA_ERROR);
        } else {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }
    }
}
