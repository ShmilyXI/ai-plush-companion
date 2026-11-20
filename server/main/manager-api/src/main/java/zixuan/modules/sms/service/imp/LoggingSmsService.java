package zixuan.modules.sms.service.imp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import zixuan.modules.sms.service.SmsService;

/**
 * 只用于本机开发：不真正发短信，把验证码写进服务日志。
 *
 * 必须显式设置 zixuan.sms.log-codes=true（环境变量 ZIXUAN_SMS_LOG_CODES=true）才会启用，
 * 默认不存在这个实现。任何能被外部访问的环境都不能开启，否则看得到日志的人可以登录任意手机号。
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "zixuan.sms.log-codes", havingValue = "true")
public class LoggingSmsService implements SmsService {

    /**
     * 固定的验证码，例如 000000。只在这个日志模式里才读取，所以必须先开启 zixuan.sms.log-codes。
     * 设置后，任何手机号都可以直接用它登录，不需要先点“获取验证码”。
     */
    private final String fixedCode;

    public LoggingSmsService(@Value("${zixuan.sms.fixed-code:}") String fixedCode) {
        String value = fixedCode == null ? "" : fixedCode.trim();
        if (!value.isEmpty() && !value.matches("\\d{4,8}")) {
            throw new IllegalStateException("zixuan.sms.fixed-code 必须是 4 到 8 位数字");
        }
        this.fixedCode = value.isEmpty() ? null : value;
    }

    @PostConstruct
    void warnOnStartup() {
        log.warn("短信验证码日志模式已开启：验证码不会发送到手机，只写入本服务日志。只能用于本机开发。");
        if (fixedCode != null) {
            log.warn("固定验证码已开启：任何手机号都可以用同一个验证码登录。只能用于本机开发。");
        }
    }

    @Override
    public String fixedVerificationCode() {
        return fixedCode;
    }

    @Override
    public void sendVerificationCodeSms(String phone, String verificationCode) {
        String tail = phone == null || phone.length() < 4 ? "" : phone.substring(phone.length() - 4);
        log.warn("[本机调试] 尾号 {} 的短信验证码：{}", tail, verificationCode);
    }
}
