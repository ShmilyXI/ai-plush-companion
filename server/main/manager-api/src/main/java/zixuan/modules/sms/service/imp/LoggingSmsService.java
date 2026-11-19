package zixuan.modules.sms.service.imp;

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

    @PostConstruct
    void warnOnStartup() {
        log.warn("短信验证码日志模式已开启：验证码不会发送到手机，只写入本服务日志。只能用于本机开发。");
    }

    @Override
    public void sendVerificationCodeSms(String phone, String verificationCode) {
        String tail = phone == null || phone.length() < 4 ? "" : phone.substring(phone.length() - 4);
        log.warn("[本机调试] 尾号 {} 的短信验证码：{}", tail, verificationCode);
    }
}
