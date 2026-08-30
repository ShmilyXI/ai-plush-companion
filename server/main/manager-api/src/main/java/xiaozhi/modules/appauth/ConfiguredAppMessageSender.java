package xiaozhi.modules.appauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import xiaozhi.modules.sms.service.SmsService;

/** Routes OTP delivery to the existing SMS service or configured SMTP. */
public final class ConfiguredAppMessageSender implements AppMessageSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfiguredAppMessageSender.class);
    private final SmsService smsService;
    private final JavaMailSender mailSender;
    private final String from;
    private final String subject;

    public ConfiguredAppMessageSender(SmsService smsService, JavaMailSender mailSender, String from, String subject) {
        this.smsService = smsService;
        this.mailSender = mailSender;
        this.from = from;
        this.subject = subject == null || subject.isBlank() ? "AI 陪伴验证码" : subject;
    }

    @Override
    public void send(String channel, String normalizedValue, String code, String purpose) {
        if ("phone".equals(channel)) {
            if (smsService == null) throw new IllegalStateException("SMS delivery is not configured");
            smsService.sendVerificationCodeSms(normalizedValue, code);
            LOGGER.info("App OTP delivery channel=phone purpose={} result=success", purpose);
            return;
        }
        if ("email".equals(channel)) {
            if (mailSender == null) throw new IllegalStateException("email delivery is not configured");
            SimpleMailMessage message = new SimpleMailMessage();
            if (from != null && !from.isBlank()) message.setFrom(from);
            message.setTo(normalizedValue);
            message.setSubject(subject);
            message.setText("你的 AI 陪伴验证码是 " + code + "，10 分钟内有效。");
            mailSender.send(message);
            LOGGER.info("App OTP delivery channel=email purpose={} result=success", purpose);
            return;
        }
        throw new IllegalArgumentException("unsupported message channel");
    }
}
