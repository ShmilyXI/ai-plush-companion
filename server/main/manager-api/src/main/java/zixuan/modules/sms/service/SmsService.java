package zixuan.modules.sms.service;

/**
 * 短信服务的方法定义接口
 *
 * @author zjy
 * @since 2025-05-12
 */
public interface SmsService {

    /**
     * 发送验证码短信
     * @param phone 手机号码
     * @param VerificationCode 验证码
     */
    void sendVerificationCodeSms(String phone, String VerificationCode) ;

    /**
     * 本机调试用的固定验证码。返回空表示没有，验证码照常随机生成。
     * 真实的短信通道永远返回空：只要有一个固定的验证码，任何人都能登录任意手机号。
     */
    default String fixedVerificationCode() {
        return null;
    }
}
