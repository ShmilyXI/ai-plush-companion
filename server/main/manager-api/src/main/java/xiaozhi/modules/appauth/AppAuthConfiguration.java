package xiaozhi.modules.appauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;

import xiaozhi.modules.sms.service.SmsService;

/** Provides a replaceable delivery adapter until an SMS/mail provider is configured. */
@Configuration
public class AppAuthConfiguration {
    @Bean
    public AppMessageSender appMessageSender(SmsService smsService,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${spring.mail.from:}") String from,
            @Value("${companion.app-auth.email-subject:AI 陪伴验证码}") String subject) {
        return new ConfiguredAppMessageSender(smsService, mailSender.getIfAvailable(), from, subject);
    }
}
