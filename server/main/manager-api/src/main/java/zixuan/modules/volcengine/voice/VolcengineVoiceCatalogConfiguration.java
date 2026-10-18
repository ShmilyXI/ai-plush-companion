package zixuan.modules.volcengine.voice;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VolcengineVoiceCatalogConfiguration {
    @Bean
    HttpClient volcengineVoiceHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Bean
    VolcengineRequestSigner volcengineRequestSigner() {
        return new VolcengineRequestSigner(Clock.systemUTC());
    }
}
