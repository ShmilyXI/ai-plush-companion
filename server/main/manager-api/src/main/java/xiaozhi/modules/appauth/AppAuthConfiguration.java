package xiaozhi.modules.appauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides a replaceable delivery adapter until an SMS/mail provider is configured. */
@Configuration
public class AppAuthConfiguration {
    @Bean
    public AppMessageSender appMessageSender() {
        return AppMessageSender.noop();
    }
}
